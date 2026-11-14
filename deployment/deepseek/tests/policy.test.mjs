import assert from 'node:assert/strict';
import { test } from 'node:test';
import { configuredPolicy, forcePolicy, installPolicy, probeSandbox } from '../policy-core.mjs';

function fixture(mode = 'danger-full-access', approval = 'ask') {
    const listeners = new Map();
    let guard;
    const events = [];
    const pending = ['already queued input'];
    const agent = {
        session: { header: { cwd: '/workspace' }, mode, approval },
        inbox: { pending, clear() { pending.splice(0); events.push(['clear']); } },
    };
    const ctx = {
        on(name, listener) { listeners.set(name, listener); },
        sandboxPolicy: { resolve({ session }) {
            return { mode: session.mode, workspaceRoot: session.header.cwd };
        } },
        approval: { overrideOf(session) { return session.approval; } },
        sessions: { async flush(session) { events.push(['flush', session.mode, session.approval]); } },
        tools: { guard(callback) { guard = callback; } },
    };
    const setters = {
        setSandboxMode(session, value) { session.mode = value; events.push(['mode', value]); },
        setApprovalPolicy(session, value) { session.approval = value; events.push(['approval', value]); },
    };
    return { ctx, agent, setters, listeners, events, guard: (execution) => guard(execution) };
}

test('invalid local permissions fail before policy registration', () => {
    assert.throws(() => configuredPolicy({ BRIDGE_DEEPSEEK_SANDBOX_MODE: 'auto' }), /INVALID_SANDBOX_MODE/);
    assert.equal(configuredPolicy({}).mode, 'read-only');
});

test('fresh and resumed historical permissions are downgraded and flushed', async () => {
    for (const oldMode of ['read-only', 'workspace-write', 'danger-full-access']) {
        const f = fixture(oldMode);
        await forcePolicy(f.ctx, f.agent, { mode: 'read-only' }, f.setters);
        assert.equal(f.agent.session.mode, 'read-only');
        assert.equal(f.agent.session.approval, 'never');
        assert.deepEqual(f.events.at(-1), ['flush', 'read-only', 'never']);
    }
});

test('pre-step reasserts after other admission listeners and before returning', async () => {
    const f = fixture();
    installPolicy(f.ctx, { mode: 'workspace-write' }, f.setters);
    const original = { kind: 'enter', messages: [] };
    const returned = await f.listeners.get('agent/pre-step')({ agent: f.agent, signal: new AbortController().signal }, async () => {
        f.agent.session.mode = 'danger-full-access';
        return original;
    });
    assert.equal(returned, original);
    assert.equal(f.agent.session.mode, 'workspace-write');
    assert.deepEqual(f.events.at(-1), ['flush', 'workspace-write', 'never']);
});

test('a child Agent receives the same policy through the shared created hook', async () => {
    const f = fixture();
    installPolicy(f.ctx, { mode: 'read-only' }, f.setters);
    const child = fixture().agent;
    await f.listeners.get('agent/created')({ agent: child });
    assert.equal(child.session.mode, 'read-only');
    assert.equal(child.session.approval, 'never');
});

test('resume discards historical durable inbox before policy flush', async () => {
    const f = fixture();
    installPolicy(f.ctx, { mode: 'read-only' }, f.setters);
    await f.listeners.get('agent/created')({ agent: f.agent, source: 'resume' });
    assert.deepEqual(f.agent.inbox.pending, []);
    assert.deepEqual(f.events[0], ['clear']);
    assert.deepEqual(f.events.at(-1), ['flush', 'read-only', 'never']);
});

test('fresh startup and child setup retain their queued initial prompt', async () => {
    const f = fixture();
    installPolicy(f.ctx, { mode: 'read-only' }, f.setters);
    await f.listeners.get('agent/created')({ agent: f.agent, source: 'startup' });
    assert.deepEqual(f.agent.inbox.pending, ['already queued input']);
    assert.equal(f.events.some(([name]) => name === 'clear'), false);
});

test('cancelled admission never enters a model step', async () => {
    const f = fixture();
    installPolicy(f.ctx, { mode: 'read-only' }, f.setters);
    const controller = new AbortController();
    controller.abort();
    await assert.rejects(f.listeners.get('agent/pre-step')({ agent: f.agent, signal: controller.signal }, async () => ({ kind: 'enter' })));
    assert.equal(f.events.length, 0);
});

test('final tool guard rejects permission drift even after a permissive listener', async () => {
    const f = fixture();
    installPolicy(f.ctx, { mode: 'read-only' }, f.setters);
    await f.listeners.get('tools/pre-execute')({ agent: f.agent }, async () => {
        f.agent.session.mode = 'danger-full-access';
        return { kind: 'allow' };
    });
    assert.match(f.guard({ agent: f.agent, name: 'bash' }), /could not verify/);
});

test('model configuration, detached work and interactive tools are denied', async () => {
    const f = fixture();
    installPolicy(f.ctx, { mode: 'read-only' }, f.setters);
    await forcePolicy(f.ctx, f.agent, { mode: 'read-only' }, f.setters);
    for (const name of ['job_start', 'goal_set', 'workflow', 'plugin_install', 'set_model', 'permission', 'ask_user_question', 'run_code']) {
        assert.match(f.guard({ agent: f.agent, name }), /disabled/);
    }
    assert.equal(f.guard({ agent: f.agent, name: 'read' }), undefined);
    assert.equal(f.guard({ agent: f.agent, name: 'subagent' }), undefined);
    assert.match(f.guard({ name: 'bash' }), /could not verify/);
});

test('storage failure blocks new Agent publication', async () => {
    const f = fixture();
    f.ctx.sessions.flush = async () => { throw new Error('storage failed'); };
    installPolicy(f.ctx, { mode: 'read-only' }, f.setters);
    await assert.rejects(f.listeners.get('agent/created')({ agent: f.agent }), /storage failed/);
});

test('sandbox preflight rejects unavailable, partial and ineffective enforcement', async () => {
    await assert.rejects(probeSandbox({ async confine() { throw new Error('unavailable'); } }, { mode: 'read-only' }), /unavailable/);
    await assert.rejects(probeSandbox({ async confine() { return { enforcement: 'partial' }; } }, { mode: 'workspace-write' }), /PARTIAL_SANDBOX/);
    const fake = { async confine(argv) { return { enforcement: 'full', argv }; } };
    await assert.rejects(probeSandbox(fake, { mode: 'read-only' }, async () => ({ inside: true, outside: true })), /BOUNDARY_FAILED/);
});

test('full access preflight verifies real writes without requesting confinement', async () => {
    await probeSandbox({ async confine() { throw new Error('must not confine full access'); } }, { mode: 'danger-full-access' });
});
