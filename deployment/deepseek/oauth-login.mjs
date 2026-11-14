// Foreground-only subscription login through the official authorization seam.
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline/promises';
import { spawn } from 'node:child_process';

const args = process.argv.slice(2);
const runtimeIndex = args.indexOf('--runtime-package');
const runtimePackage = runtimeIndex < 0
    ? new URL('./runtime/package.json', import.meta.url)
    : args[runtimeIndex + 1];
if (runtimeIndex >= 0) {
    args.splice(runtimeIndex, 2);
}
const noOpen = args.includes('--no-open');
const check = args.includes('--check');
if (args.some((argument) => !['--no-open', '--check'].includes(argument)) || !process.env.DSH_HOME) {
    throw new Error('BRIDGE_DEEPSEEK_OAUTH_ARGUMENTS_REQUIRED');
}
const require = createRequire(runtimePackage);
const load = async (name) => import(pathToFileURL(require.resolve(name)).href);
const { Context } = await load('@deepseek-ai/cordis');
const { default: CredentialsLocal } = await load('@deepseek-ai/dsh-credentials-local');
const { default: Authorization } = await load('@deepseek-ai/dsh-authorization');
const { default: Llm } = await load('@deepseek-ai/dsh-llm');
const PiAi = await load('@deepseek-ai/dsh-llm-pi-ai');
const { credentialKey } = await load('@deepseek-ai/dsh-credentials');
const ctx = new Context();
let terminal;
let inputClosed = false;
const cancelled = new AbortController();
const stop = () => cancelled.abort(new Error('foreground login cancelled'));
function browserOrManualInput(prompt) {
    const signal = prompt.signal ?? cancelled.signal;
    if (terminal === undefined) {
        terminal = createInterface({ input: process.stdin, output: process.stderr, terminal: process.stdin.isTTY });
        terminal.once('close', () => { inputClosed = true; });
    }
    return new Promise((resolve, reject) => {
        const finish = (callback, value) => {
            signal.removeEventListener('abort', abort);
            callback(value);
        };
        const abort = () => finish(reject, new Error('authorization input cancelled'));
        signal.addEventListener('abort', abort, { once: true });
        if (signal.aborted) {
            abort();
            return;
        }
        // A controller may deliberately provide no stdin. EOF means there is
        // no manual fallback, while the OAuth callback must keep waiting.
        if (inputClosed || process.stdin.readableEnded) {
            return;
        }
        try {
            terminal.question('Complete authorization in the browser; paste a redirect only if the callback is unavailable: ', { signal })
                .then((answer) => finish(resolve, answer), (error) => {
                    if (!inputClosed || signal.aborted) {
                        finish(reject, error);
                    }
                });
        } catch (error) {
            if (!inputClosed || signal.aborted) {
                finish(reject, error);
            }
        }
    });
}
process.once('SIGINT', stop);
process.once('SIGTERM', stop);
try {
    await ctx.plugin(CredentialsLocal, { dshHome: process.env.DSH_HOME, watch: false });
    await ctx.plugin(Authorization);
    await ctx.plugin(Llm);
    await ctx.plugin(PiAi, { providers: { 'openai-codex': {} } });
    const key = credentialKey('llm-pi-ai', 'openai-codex');
    const flow = ctx.authorization.describe(key);
    if (!flow?.methods.some((method) => method.id === 'oauth')) {
        throw new Error('BRIDGE_DEEPSEEK_OAUTH_FLOW_UNAVAILABLE');
    }
    if (check) {
        process.stdout.write(JSON.stringify({ provider: 'openai-codex', method: 'oauth', available: true }) + '\n');
    } else {
        const interaction = {
            notify(notice) {
                if (notice.url !== undefined) {
                    // This is an interactive handoff, not a service log. The
                    // controller must keep this temporary URL out of logs.
                    process.stdout.write(JSON.stringify({ type: 'authorization-url', url: notice.url }) + '\n');
                    if (!noOpen) {
                        const command = process.platform === 'darwin' ? 'open' : 'xdg-open';
                        const child = spawn(command, [notice.url], { cwd: process.cwd(), stdio: 'ignore' });
                        child.on('error', () => {});
                        child.unref();
                    }
                } else {
                    process.stdout.write(JSON.stringify({ type: 'progress', message: 'Waiting for authorization.' }) + '\n');
                }
            },
            async prompt(prompt) {
                if (prompt.kind === 'select') {
                    const browser = prompt.options.find((option) => option.id === 'browser');
                    if (browser === undefined) {
                        throw new Error('BRIDGE_DEEPSEEK_BROWSER_LOGIN_UNAVAILABLE');
                    }
                    return browser.id;
                }
                return browserOrManualInput(prompt);
            },
        };
        const result = await ctx.authorization.begin({ key, method: 'oauth', interaction, signal: cancelled.signal });
        const metadata = await ctx.credentials.describeRecord(key);
        if (result.status !== 'authorized' || !metadata.configured || metadata.kind !== 'grant') {
            throw new Error('BRIDGE_DEEPSEEK_OAUTH_NOT_COMMITTED');
        }
        process.stdout.write(JSON.stringify({ type: 'authorized', configured: true, kind: 'grant' }) + '\n');
    }
} catch {
    process.stderr.write('BRIDGE_DEEPSEEK_OAUTH_FAILED\n');
    process.exitCode = 1;
} finally {
    terminal?.close();
    process.removeListener('SIGINT', stop);
    process.removeListener('SIGTERM', stop);
    await ctx.fiber.dispose();
}
