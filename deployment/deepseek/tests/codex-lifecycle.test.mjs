import assert from 'node:assert/strict';
import { test } from 'node:test';
import { PassThrough, Writable } from 'node:stream';
import { guardCodexProcess } from '../codex-lifecycle.mjs';

const tick = () => new Promise((resolve) => setImmediate(resolve));
const frame = (value) => `${JSON.stringify({ jsonrpc: '2.0', ...value })}\n`;

function fixture({ autoExit = true } = {}) {
    const done = Promise.withResolvers();
    const range = Promise.withResolvers();
    const writes = [];
    let eof = 0;
    let terminated = 0;
    const stdin = new Writable({
        write(chunk, encoding, callback) { writes.push(chunk.toString()); callback(); },
        final(callback) { eof++; callback(); },
    });
    const stdout = new PassThrough();
    const child = {
        stdin, stdout, done: done.promise, stderr: undefined, control: undefined, collected: {},
        terminate() {
            terminated++;
            if (autoExit) {
                done.resolve({ exitCode: 0, signal: null });
                range.resolve(true);
            }
        },
        async waitForExit(signal) {
            if (signal?.aborted) {
                return false;
            }
            return range.promise;
        },
    };
    const guarded = guardCodexProcess(child);
    return {
        child, guarded, done, range,
        get eof() { return eof; }, get terminated() { return terminated; },
        get sent() { return writes.join('').split('\n').filter(Boolean).map((line) => JSON.parse(line)); },
        async send(value) {
            await new Promise((resolve, reject) => guarded.stdin.write(frame(value), (error) => error ? reject(error) : resolve()));
        },
        receive(value) { stdout.write(frame(value)); },
        finish() { done.resolve({ exitCode: null, signal: 'SIGKILL' }); range.resolve(true); },
    };
}

async function start(f, { id = 'start', thread = 'thread', turn = 'turn', accepted = true } = {}) {
    await f.send({ id, method: 'turn/start', params: { threadId: thread, input: [] } });
    if (accepted) {
        f.receive({ id, result: { turn: { id: turn } } });
    }
}

function close(f) {
    f.guarded.stdin.end();
    f.guarded.terminate();
    return f.guarded.waitForExit();
}

function terminal(f, { thread = 'thread', turn = 'turn', status = 'interrupted' } = {}) {
    f.receive({ method: 'turn/completed', params: { threadId: thread, turn: { id: turn, status } } });
}

async function remainsHeld(f, waiting) {
    let released = false;
    void waiting.then(() => { released = true; });
    await tick();
    assert.equal(released, false);
    assert.equal(f.eof, 0);
    assert.equal(f.terminated, 0);
}

test('cancel sends native interrupt and holds EOF, terminate and exit until matching terminal', async () => {
    const f = fixture();
    await start(f);
    const waiting = close(f);
    await remainsHeld(f, waiting);
    const interruption = f.sent.find((item) => item.method === 'turn/interrupt');
    assert.match(interruption.id, /^bridge_cancel_/u);
    assert.deepEqual(interruption.params, { threadId: 'thread', turnId: 'turn' });
    f.receive({ id: interruption.id, result: {} });
    terminal(f, { thread: 'another-thread' });
    await remainsHeld(f, waiting);
    terminal(f);
    assert.equal(await waiting, true);
    assert.equal(f.eof, 1);
    assert.equal(f.terminated, 1);
});

test('late turn id from an early notification gets interrupted after official reader detaches', async () => {
    const f = fixture();
    await start(f, { accepted: false });
    const officialReader = () => {};
    f.child.stdout.on('data', officialReader);
    const waiting = close(f);
    f.child.stdout.off('data', officialReader);
    await remainsHeld(f, waiting);
    assert.equal(f.sent.some((item) => item.method === 'turn/interrupt'), false);
    const started = frame({ method: 'turn/started', params: { threadId: 'thread', turn: { id: 'turn' } } });
    f.child.stdout.write(started.slice(0, 13));
    f.child.stdout.write(started.slice(13));
    assert.equal(f.sent.filter((item) => item.method === 'turn/interrupt').length, 1);
    terminal(f);
    await remainsHeld(f, waiting);
    f.receive({ id: 'start', result: { turn: { id: 'turn' } } });
    assert.equal(await waiting, true);
});

test('late turn/start response supplies the id needed for pending stop', async () => {
    const f = fixture();
    await start(f, { accepted: false });
    const waiting = close(f);
    f.receive({ id: 'unrelated', result: { turn: { id: 'foreign' } } });
    await remainsHeld(f, waiting);
    f.receive({ id: 'start', result: { turn: { id: 'turn' } } });
    assert.equal(f.sent.filter((item) => item.method === 'turn/interrupt').length, 1);
    terminal(f);
    assert.equal(await waiting, true);
});

test('an official interruption already sent is not duplicated', async () => {
    const f = fixture();
    await start(f);
    await f.send({ id: 'req_official', method: 'turn/interrupt', params: { threadId: 'thread', turnId: 'turn' } });
    const waiting = close(f);
    assert.equal(f.sent.filter((item) => item.method === 'turn/interrupt').length, 1);
    terminal(f);
    assert.equal(await waiting, true);
});

test('normal completion permits idle process cleanup without interrupting a completed turn', async () => {
    for (const status of ['completed', 'failed', 'interrupted']) {
        const f = fixture();
        await start(f);
        terminal(f, { status });
        assert.equal(await close(f), true);
        assert.equal(f.sent.some((item) => item.method === 'turn/interrupt'), false);
        assert.equal(f.eof, 1);
        assert.equal(f.terminated, 1);
    }
});

test('stdout loss while process is alive holds until externally terminated range exits', async () => {
    const f = fixture({ autoExit: false });
    await start(f);
    f.child.stdout.end();
    const waiting = close(f);
    await remainsHeld(f, waiting);
    f.finish();
    assert.equal(await waiting, true);
    assert.equal(f.terminated, 1);
});

test('a dead app-server permits failure cleanup but cannot claim a still-live range exited', async () => {
    const f = fixture({ autoExit: false });
    await start(f);
    const waiting = close(f);
    f.done.resolve({ exitCode: 0, signal: null });
    let exited = false;
    void waiting.then(() => { exited = true; });
    await tick();
    assert.equal(exited, false);
    assert.equal(f.eof, 1);
    assert.equal(f.terminated, 1);
    f.range.resolve(true);
    assert.equal(await waiting, true);
    assert.equal(f.terminated, 1);
});

test('separate children cannot release, interrupt or terminate each other', async () => {
    const first = fixture();
    const second = fixture();
    await start(first, { thread: 'thread-a', turn: 'turn-a' });
    await start(second, { thread: 'thread-b', turn: 'turn-b' });
    const waiting = close(first);
    terminal(first, { thread: 'thread-b', turn: 'turn-b' });
    terminal(second, { thread: 'thread-b', turn: 'turn-b', status: 'completed' });
    await remainsHeld(first, waiting);
    assert.equal(second.sent.some((item) => item.method === 'turn/interrupt'), false);
    assert.equal(second.eof, 0);
    assert.equal(second.terminated, 0);
    assert.equal(await close(second), true);
    terminal(first, { thread: 'thread-a', turn: 'turn-a' });
    assert.equal(await waiting, true);
});

test('startup before turn submission may be cleaned without any native model turn', async () => {
    const f = fixture();
    await f.send({ id: 'initialize', method: 'initialize', params: {} });
    assert.equal(await close(f), true);
    assert.equal(f.sent.some((item) => item.method === 'turn/interrupt'), false);
});

test('only definite start rejection codes with no observed turn allow cleanup', async () => {
    for (const code of [-32600, -32601, -32602, -32000]) {
        const f = fixture();
        await start(f, { accepted: false });
        f.receive({ id: 'start', error: { code, message: 'not retained' } });
        assert.equal(await close(f), true);
    }
    for (const code of [-32603, -31999]) {
        const f = fixture({ autoExit: false });
        await start(f, { accepted: false });
        f.receive({ id: 'start', error: { code, message: 'not retained' } });
        const waiting = close(f);
        await remainsHeld(f, waiting);
        f.finish();
        assert.equal(await waiting, true);
    }
});

test('an early turn rules out the definite rejection exception', async () => {
    const f = fixture({ autoExit: false });
    await start(f, { accepted: false });
    f.receive({ method: 'turn/started', params: { threadId: 'thread', turn: { id: 'turn' } } });
    f.receive({ id: 'start', error: { code: -32602 } });
    const waiting = close(f);
    await remainsHeld(f, waiting);
    f.finish();
    assert.equal(await waiting, true);
});

test('an aborted wait returns false without opening the lifecycle gate', async () => {
    const f = fixture();
    await start(f);
    const controller = new AbortController();
    const waiting = f.guarded.waitForExit(controller.signal);
    controller.abort();
    assert.equal(await waiting, false);
    const disposal = close(f);
    await remainsHeld(f, disposal);
    terminal(f);
    assert.equal(await disposal, true);
});

test('a foreign turn terminal is ignored while the correct turn still needs interruption', async () => {
    const f = fixture();
    await start(f);
    const waiting = close(f);
    terminal(f, { turn: 'foreign-turn' });
    await remainsHeld(f, waiting);
    terminal(f);
    assert.equal(await waiting, true);
});

test('a failed done observation does not prove that a still-live process can be killed', async () => {
    const f = fixture({ autoExit: false });
    await start(f);
    const waiting = close(f);
    f.done.reject(new Error('provider observation failed'));
    await remainsHeld(f, waiting);
    f.range.resolve(true);
    assert.equal(await waiting, true);
    assert.equal(f.terminated, 0);
});
