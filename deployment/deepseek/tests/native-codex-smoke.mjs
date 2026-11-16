// Explicit, keyless real-native smoke. Not a *.test.mjs: default offline checks do not run it.
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { activateCodexProvider } from '../bridge-codex.mjs';
import { startResponsesFixture } from './native-responses-fixture.mjs';

const argument = process.argv.indexOf('--runtime');
const manifest = argument < 0
    ? fileURLToPath(new URL('../../../build/deepseek-runtime-subagent/runtime/package.json', import.meta.url))
    : join(resolve(process.argv[argument + 1]), 'package.json');
const require = createRequire(manifest);
const load = async (name) => import(pathToFileURL(require.resolve(name)).href);
const root = await mkdtemp(join(tmpdir(), 'bridge-native-codex-smoke-'));
const home = join(root, 'os-home');
const codexHome = join(root, 'codex-home');
const workspace = join(root, 'workspace');
for (const directory of [home, codexHome, workspace, join(root, 'tmp')]) {
    await mkdir(directory);
}
for (const key of Object.keys(process.env)) {
    if (!['SystemRoot', 'SYSTEMROOT', 'WINDIR'].includes(key)) {
        delete process.env[key];
    }
}
Object.assign(process.env, {
    HOME: home, USERPROFILE: home, CODEX_HOME: codexHome,
    XDG_CONFIG_HOME: join(root, 'xdg'), TMPDIR: join(root, 'tmp'), TMP: join(root, 'tmp'),
    TEMP: join(root, 'tmp'), PATH: '/usr/bin:/bin', SHELL: '/bin/sh', LANG: 'C.UTF-8',
    NO_PROXY: '127.0.0.1,localhost', DSH_TELEMETRY_DISABLED: '1',
});

async function bounded(promise, label) {
    let timer;
    try {
        return await Promise.race([promise, new Promise((_, reject) => {
            timer = setTimeout(() => reject(new Error(`Native fixture timed out: ${label}`)), 30000);
        })]);
    } finally {
        clearTimeout(timer);
    }
}

function observeFrames(stream, accept) {
    let buffer = '';
    stream.on('data', (chunk) => {
        buffer += chunk.toString('utf8');
        while (buffer.includes('\n')) {
            const boundary = buffer.indexOf('\n');
            const line = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 1);
            if (line.trim()) {
                accept(JSON.parse(line));
            }
        }
    });
}

function observeChild(child) {
    const events = [];
    let outgoing = '';
    const write = child.stdin.write.bind(child.stdin);
    child.stdin.write = (...arguments_) => {
        outgoing += arguments_[0].toString();
        while (outgoing.includes('\n')) {
            const boundary = outgoing.indexOf('\n');
            const frame = JSON.parse(outgoing.slice(0, boundary));
            outgoing = outgoing.slice(boundary + 1);
            events.push({ direction: 'out', method: frame.method });
        }
        return write(...arguments_);
    };
    observeFrames(child.stdout, (frame) => {
        events.push({ direction: 'in', method: frame.method,
            status: frame.method === 'turn/completed' ? frame.params.turn.status : undefined });
    });
    const terminate = child.terminate.bind(child);
    child.terminate = () => {
        events.push({ direction: 'owner', method: 'terminate' });
        terminate();
    };
    const record = { child, events, exited: false };
    void child.done.then(() => { record.exited = true; });
    return record;
}

const children = [];
const runs = [];
let context;
let fixture;
try {
    fixture = await startResponsesFixture();
    await writeFile(join(codexHome, 'config.toml'), [
        'model = "fixture-model"', 'model_provider = "fixture"',
        'approval_policy = "on-request"', 'sandbox_mode = "read-only"',
        'disable_response_storage = true', 'check_for_update_on_startup = false',
        'web_search = "disabled"', '', '[model_providers.fixture]',
        'name = "Loopback keyless fixture"', `base_url = "${fixture.baseUrl}"`,
        'wire_api = "responses"', 'requires_openai_auth = false',
        'request_max_retries = 0', 'stream_max_retries = 0', '',
        '[analytics]', 'enabled = false', '', '[feedback]', 'enabled = false', '',
    ].join('\n'));
    const { Context } = await load('@deepseek-ai/cordis');
    const { default: Subprocess } = await load('@deepseek-ai/dsh-subprocess-local');
    const official = await load('@deepseek-ai/dsh-subagent-codex');
    const codexPackage = JSON.parse(await readFile(require.resolve('@openai/codex/package.json'), 'utf8'));
    assert.equal(codexPackage.version, '0.153.4');
    context = new Context();
    await context.plugin(Subprocess);
    let provider;
    let ready;
    const api = {
        logger: { warn() {} },
        subprocess: { spawn(spec) {
            assert.equal(spec.cwd, workspace);
            assert.deepEqual(spec.argv, [process.execPath,
                join(dirname(require.resolve('@openai/codex/package.json')), 'bin/codex.js'),
                'app-server', '--stdio']);
            assert.equal(spec.env.CODEX_HOME, codexHome);
            const child = context.subprocess.spawn(spec);
            children.push(observeChild(child));
            return child;
        } },
        subagents: { registerProvider(value) { provider = value; } },
        provide(name, value) { assert.equal(name, 'bridgeDeepSeekCodex'); ready = value; },
    };
    await activateCodexProvider(api, {
        enabled: true, mode: 'danger-full-access', codexHome, model: 'fixture-model',
    }, async () => official);
    assert.deepEqual(ready, { version: 1, enabled: true });
    assert.equal(provider.name, 'codex');
    const start = async (text) => {
        const run = await bounded(provider.start({
            cwd: workspace, parent: { session: { header: { cwd: workspace } } },
            prompt: [{ type: 'text', text }], signal: new AbortController().signal,
        }), 'provider startup');
        runs.push(run);
        return run;
    };
    const finish = async (run, expected) => {
        const result = await bounded(run.result, 'fixture result');
        assert.deepEqual(result, { output: [{ type: 'text', text: expected }], stopReason: 'completed' });
        await bounded(run.dispose(), 'completed disposal');
    };

    fixture.enqueue({ text: 'NATIVE_CODEX_MARKER_OK' });
    await finish(await start('Return the fixture marker.'), 'NATIVE_CODEX_MARKER_OK');
    console.log('PASS: Bridge adapter → official provider → pinned native app-server → loopback Responses marker');

    const target = join(workspace, 'full-access-write.txt');
    const quoted = `'${target.replaceAll("'", "'\\''")}'`;
    const command = `printf native-write > ${quoted}`;
    fixture.enqueue({ choices: [
        { name: 'exec_command', arguments: { cmd: command } },
        { name: 'shell_command', arguments: { command } },
    ] }, { text: 'NATIVE_CODEX_WRITE_OK' });
    await finish(await start('Execute the fixture write.'), 'NATIVE_CODEX_WRITE_OK');
    assert.equal(await readFile(target, 'utf8'), 'native-write');
    console.log('PASS: fixed full-access mapping executes a real native tool write inside the temporary workspace');

    const firstStarted = fixture.hold('cancelled');
    const cancelled = await start('Hold this request for cancellation.');
    await bounded(firstStarted, 'cancelled request arrival');
    const cancelledChild = children.at(-1);
    const siblingStarted = fixture.hold('sibling');
    const sibling = await start('Hold an independent sibling request.');
    await bounded(siblingStarted, 'sibling request arrival');
    const siblingChild = children.at(-1);
    const disposing = cancelled.dispose();
    assert.equal(cancelledChild.events.some((event) => event.method === 'terminate'), false);
    assert.equal((await bounded(cancelled.result, 'cancel result')).stopReason, 'aborted');
    await bounded(disposing, 'cancel cleanup barrier');
    const interrupt = cancelledChild.events.findIndex((event) => event.direction === 'out' && event.method === 'turn/interrupt');
    const terminal = cancelledChild.events.findIndex((event) => event.direction === 'in' && event.method === 'turn/completed');
    const termination = cancelledChild.events.findIndex((event) => event.method === 'terminate');
    assert.ok(interrupt >= 0 && terminal > interrupt && termination > terminal);
    assert.equal(cancelledChild.events[terminal].status, 'interrupted');
    assert.equal(siblingChild.exited, false);
    assert.equal(siblingChild.events.some((event) => event.method === 'terminate' || event.method === 'turn/interrupt'), false);
    fixture.finish('sibling', 'NATIVE_CODEX_SIBLING_OK');
    await finish(sibling, 'NATIVE_CODEX_SIBLING_OK');
    console.log('PASS: native turn/interrupt → interrupted terminal → owned termination; concurrent sibling remains alive');

    for (const record of children) {
        assert.equal(await bounded(record.child.waitForExit(), 'managed-range exit'), true);
    }
    console.log(`PASS: ${children.length} native owned process ranges are quiescent; ${fixture.requests.length} loopback requests, no account auth`);
} finally {
    // Failure cleanup owns only these temporary native processes, never desktop/Bridge services.
    for (const record of children) {
        record.child.terminate();
    }
    await Promise.allSettled(runs.map((run) => run.dispose()));
    if (context !== undefined) {
        await context.fiber.dispose();
    }
    if (fixture !== undefined) {
        await fixture.close();
    }
    await rm(root, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
}
