import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { configuredPolicy, installPolicy, probeSandbox } from './policy-core.mjs';

const require = createRequire(new URL('./runtime/package.json', import.meta.url));
const { setSandboxMode } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-sandbox-policy')).href);
const { setApprovalPolicy } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-user-approval')).href);

export const name = 'bridge-deepseek-policy';
export const inject = ['agents', 'sessions', 'sandbox', 'sandboxPolicy', 'approval', 'tools'];

export async function apply(ctx) {
    const policy = configuredPolicy();
    await probeSandbox(ctx.sandbox, policy);
    const pin = installPolicy(ctx, policy, { setSandboxMode, setApprovalPolicy });
    for (const agent of ctx.agents.list()) {
        await pin(agent);
    }
    ctx.provide('bridgeDeepSeekPolicy', Object.freeze({ version: 1, mode: policy.mode }));
}
