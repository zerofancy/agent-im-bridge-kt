import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';

export const MODES = ['read-only', 'workspace-write', 'danger-full-access'];
const BLOCKED = /(?:^|_)(?:jobs?|goals?|workflow|plugins?|config|permission|models?|plan)(?:_|$)/;
const INTERACTIVE = new Set(['ask_user_question', 'enter_plan_mode', 'exit_plan_mode', 'working_directory']);

export function configuredPolicy(env = process.env) {
    const mode = env.BRIDGE_DEEPSEEK_SANDBOX_MODE ?? 'read-only';
    if (!MODES.includes(mode)) {
        throw new Error('BRIDGE_DEEPSEEK_INVALID_SANDBOX_MODE');
    }
    return Object.freeze({ mode });
}

export function blockedTool(name) {
    return typeof name !== 'string' || BLOCKED.test(name) || INTERACTIVE.has(name) || name === 'run_code';
}

export function assertPolicy(ctx, agent, policy) {
    const session = agent?.session;
    if (session === undefined || !isAbsolute(session.header?.cwd ?? '')) {
        throw new Error('BRIDGE_DEEPSEEK_SESSION_POLICY_UNAVAILABLE');
    }
    const resolved = ctx.sandboxPolicy.resolve({ session });
    if (resolved.mode !== policy.mode || resolved.workspaceRoot !== session.header.cwd
        || (resolved.additionalWritableRoots?.length ?? 0) !== 0
        || ctx.approval.overrideOf(session) !== 'never') {
        throw new Error('BRIDGE_DEEPSEEK_SESSION_POLICY_MISMATCH');
    }
}

export async function forcePolicy(ctx, agent, policy, setters) {
    const session = agent?.session;
    if (session === undefined || !isAbsolute(session.header?.cwd ?? '')) {
        throw new Error('BRIDGE_DEEPSEEK_SESSION_POLICY_UNAVAILABLE');
    }
    if (ctx.sandboxPolicy.resolve({ session }).mode !== policy.mode) {
        setters.setSandboxMode(session, policy.mode);
    }
    if (ctx.approval.overrideOf(session) !== 'never') {
        setters.setApprovalPolicy(session, 'never');
    }
    assertPolicy(ctx, agent, policy);
    await ctx.sessions.flush(session);
}

export function installPolicy(ctx, policy, setters) {
    const pin = (agent) => forcePolicy(ctx, agent, policy, setters);
    ctx.on('agent/created', async ({ agent, source }) => {
        // Harness persists its inbox. Bridge queues deliberately do not
        // survive restart, so discard unclaimed historical input before the
        // resumed Agent is published. Fresh child setup input stays intact.
        if (source === 'resume') {
            agent.inbox.clear();
        }
        await pin(agent);
    });
    ctx.on('agent/pre-step', async ({ agent, signal }, next) => {
        const decision = await next();
        signal.throwIfAborted();
        await pin(agent);
        return decision;
    });
    ctx.on('tools/pre-execute', async (execution, next) => {
        await pin(execution.agent);
        return next();
    });
    ctx.tools.guard((execution) => {
        if (blockedTool(execution.name)) {
            return 'This capability is disabled by the local Bridge configuration.';
        }
        try {
            assertPolicy(ctx, execution.agent, policy);
            return undefined;
        } catch {
            return 'The Bridge could not verify the local execution policy.';
        }
    });
    return pin;
}

const PROBE = `const fs = require('node:fs');
const write = (path) => { try { fs.writeFileSync(path, 'probe', {flag:'wx'}); return true; } catch { return false; } };
process.stdout.write(JSON.stringify({inside:write(process.argv[1]),outside:write(process.argv[2])}));`;

function runProbe(argv, cwd) {
    return new Promise((resolve, reject) => {
        const child = spawn(argv[0], argv.slice(1), { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
        let output = '';
        const timer = setTimeout(() => {
            child.kill('SIGKILL');
        }, 5000);
        child.stdout.setEncoding('utf8');
        child.stdout.on('data', (chunk) => {
            if (output.length < 2048) {
                output += chunk;
            }
        });
        child.stderr.resume();
        child.once('error', () => {
            clearTimeout(timer);
            reject(new Error('BRIDGE_DEEPSEEK_SANDBOX_PROBE_START_FAILED'));
        });
        child.once('close', (code) => {
            clearTimeout(timer);
            if (code !== 0) {
                reject(new Error('BRIDGE_DEEPSEEK_SANDBOX_PROBE_FAILED'));
                return;
            }
            try {
                resolve(JSON.parse(output));
            } catch {
                reject(new Error('BRIDGE_DEEPSEEK_SANDBOX_PROBE_INVALID_RESULT'));
            }
        });
    });
}

export async function probeSandbox(sandbox, policy, runner = runProbe) {
    // workspace-write deliberately grants platform /tmp. Probe beside the
    // explicit launch cwd so the outside-write attempt tests a real boundary.
    const root = await mkdtemp(join(process.cwd(), '.bridge-deepseek-sandbox-'));
    const workspace = join(root, 'workspace');
    await mkdir(workspace);
    try {
        const argv = [process.execPath, '-e', PROBE, join(workspace, 'inside'), join(root, 'outside')];
        let launch = argv;
        if (policy.mode !== 'danger-full-access') {
            const confined = await sandbox.confine(argv, { mode: policy.mode, workspaceRoot: workspace });
            if (confined.enforcement !== 'full') {
                throw new Error('BRIDGE_DEEPSEEK_PARTIAL_SANDBOX_UNSUPPORTED');
            }
            launch = confined.argv;
        }
        const result = await runner(launch, workspace);
        const expectedInside = policy.mode !== 'read-only';
        const expectedOutside = policy.mode === 'danger-full-access';
        if (result.inside !== expectedInside || result.outside !== expectedOutside) {
            throw new Error('BRIDGE_DEEPSEEK_SANDBOX_BOUNDARY_FAILED');
        }
    } finally {
        await rm(root, { recursive: true, force: true });
    }
}
