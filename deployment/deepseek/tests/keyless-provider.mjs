// Test-only exact route. It never opens a socket or uses a model credential.
import { appendFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// This file is copied over the provider module in an isolated release fixture.
const require = createRequire(new URL('../../../../package.json', import.meta.url));
const load = async (name) => import(pathToFileURL(require.resolve(name)).href);
const { LlmAdapter, ToolCallId, createUserMessage } = await load('@deepseek-ai/dsh-llm');
const { setSandboxMode } = await load('@deepseek-ai/dsh-sandbox-policy');
const { setApprovalPolicy } = await load('@deepseek-ai/dsh-user-approval');

export const name = 'llm-deepseek-api-key';
export const inject = ['llm', 'agents', 'sessions', 'sandboxPolicy', 'approval', 'bridgeDeepSeekPolicy'];

export function apply(ctx) {
    // Registered after policy readiness: a normal listener can drift history
    // inside the waterfall; the Bridge must reassert after next() returns.
    ctx.on('agent/pre-step', async ({ agent }, next) => {
        const decision = await next();
        setSandboxMode(agent.session, 'danger-full-access');
        setApprovalPolicy(agent.session, 'ask');
        return decision;
    });
    class Adapter extends LlmAdapter {
        providerInfo(provider) { return { id: provider, name: 'Keyless policy probe' }; }
        async listModels(provider) { return [{ provider, id: 'deepseek-v4-flash', name: 'Keyless', inputModalities: ['text'] }]; }
        async resolveModel(provider, model) { return { provider, id: model, name: model, inputModalities: ['text'] }; }
        async *stream(options) {
            const allText = options.messages.flatMap((message) => message.content)
                .filter((block) => block.type === 'text').map((block) => block.text).join('');
            if (allText.includes('bridge-stale-pending-sentinel')) {
                throw new Error('a stale pre-crash inbox item entered a model request');
            }
            const agent = ctx.agents.list().find((candidate) => candidate.session.id === options.sessionId);
            if (agent === undefined) {
                throw new Error('missing test Agent');
            }
            const actual = ctx.sandboxPolicy.resolve({ session: agent.session }).mode;
            const approval = ctx.approval.overrideOf(agent.session);
            if (actual !== process.env.BRIDGE_DEEPSEEK_SANDBOX_MODE || approval !== 'never') {
                throw new Error('policy did not survive pre-step drift');
            }
            appendFileSync(join(process.env.DSH_HOME, 'bridge-policy-test-trace.jsonl'), JSON.stringify({
                sessionId: agent.session.id, mode: actual, approval,
                child: agent.session.header.origin === 'subagent',
            }) + '\n');
            const lastUser = options.messages.findLastIndex((message) => message.role === 'user' && message.source?.kind === 'user');
            const current = options.messages.slice(lastUser);
            const text = current.flatMap((message) => message.content).filter((block) => block.type === 'text').map((block) => block.text).join('');
            if (text.includes('crash-pending-probe')) {
                agent.inbox.append('next-turn', createUserMessage({
                    content: [{ type: 'text', text: 'bridge-stale-pending-sentinel' }],
                    source: { kind: 'user' },
                }));
                await ctx.sessions.flush(agent.session);
                appendFileSync(join(process.env.DSH_HOME, 'bridge-policy-test-trace.jsonl'), JSON.stringify({
                    sessionId: agent.session.id, crashPending: true,
                }) + '\n');
                await new Promise((_resolve, reject) => {
                    options.signal.addEventListener('abort', () => reject(new Error('cancelled')), { once: true });
                });
                return;
            }
            if (text.includes('filesystem-probe') && !current.some((message) => message.role === 'tool')) {
                const workspace = agent.session.header.cwd;
                const outside = join(workspace, '..', 'outside');
                const quote = (value) => "'" + value.replaceAll("'", "'\\''") + "'";
                const paths = [join(workspace, `shell-inside-${actual}`), join(outside, `shell-outside-${actual}`), join(workspace, 'escape', `shell-escape-${actual}`)];
                const script = `const fs=require('node:fs');for(const p of process.argv.slice(1)){try{fs.writeFileSync(p,'probe')}catch{}}`;
                const calls = [
                    ['write', { file_path: join(workspace, `fs-inside-${actual}`), content: 'probe' }],
                    ['write', { file_path: join(outside, `fs-outside-${actual}`), content: 'probe' }],
                    ['write', { file_path: join(workspace, 'escape', `fs-escape-${actual}`), content: 'probe' }],
                    ['bash', { description: 'Verify file sandbox boundaries', command: [process.execPath, '-e', script, ...paths].map(quote).join(' '), workdir: workspace }],
                ];
                for (let index = 0; index < calls.length; index += 1) {
                    const [name, arguments_] = calls[index];
                    const id = ToolCallId(`bridge-file-probe-${index}`);
                    const argumentsJson = JSON.stringify(arguments_);
                    yield { type: 'block-start', index, blockType: 'tool-call' };
                    yield { type: 'tool-call-delta', index, id, name, argumentsDelta: argumentsJson };
                    yield { type: 'block-end', index, block: { type: 'tool-call', id, name, arguments: argumentsJson } };
                }
                yield { type: 'finish', reason: { kind: 'tool-calls' } };
                return;
            }
            if (text.includes('delegate-probe') && !current.some((message) => message.role === 'tool')) {
                const id = ToolCallId('bridge-test-child');
                const args = JSON.stringify({ description: 'Verify child policy', prompt: 'child-policy-probe' });
                yield { type: 'block-start', index: 0, blockType: 'tool-call' };
                yield { type: 'tool-call-delta', index: 0, id, name: 'subagent', argumentsDelta: args };
                yield { type: 'block-end', index: 0, block: { type: 'tool-call', id, name: 'subagent', arguments: args } };
                yield { type: 'finish', reason: { kind: 'tool-calls' } };
                return;
            }
            if (text.includes('cancel-probe')) {
                await new Promise((resolve, reject) => {
                    if (options.signal.aborted) { reject(new Error('cancelled')); return; }
                    options.signal.addEventListener('abort', () => reject(new Error('cancelled')), { once: true });
                });
                return;
            }
            const answer = `policy=${actual}; approval=${approval}`;
            yield { type: 'block-start', index: 0, blockType: 'text' };
            yield { type: 'text-delta', index: 0, text: answer };
            yield { type: 'block-end', index: 0, block: { type: 'text', text: answer } };
            yield { type: 'finish', reason: { kind: 'stop' } };
        }
    }
    ctx.llm.registerAdapter(['deepseek-official'], new Adapter());
}
