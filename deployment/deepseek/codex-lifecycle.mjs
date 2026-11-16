import { randomUUID } from 'node:crypto';
import { Writable } from 'node:stream';
import { StringDecoder } from 'node:string_decoder';

const START_REJECTION_CODES = new Set([-32600, -32601, -32602, -32000]);
const TERMINAL_STATUSES = new Set(['completed', 'interrupted', 'failed']);
const MAX_FRAME_CHARS = 16 * 1024 * 1024;
const record = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const identity = (value) => typeof value === 'string' && value.length > 0;
const rpcId = (value) => identity(value) || (typeof value === 'number' && Number.isFinite(value));

// Only transient framing buffers contain payload bytes. State retains identifiers
// and terminal facts; neither malformed frames nor external errors are logged.
function frames(onFrame, onInvalid) {
    const decoder = new StringDecoder('utf8');
    let buffered = '';
    let skipping = false;
    return (chunk) => {
        const text = typeof chunk === 'string' ? chunk : decoder.write(chunk);
        let start = 0;
        while (start < text.length) {
            const newline = text.indexOf('\n', start);
            const end = newline < 0 ? text.length : newline;
            if (!skipping) {
                buffered += text.slice(start, end);
                if (buffered.length > MAX_FRAME_CHARS) {
                    buffered = '';
                    skipping = true;
                    onInvalid();
                }
            }
            if (newline < 0) {
                return;
            }
            if (!skipping && buffered.trim()) {
                let frame;
                try {
                    frame = JSON.parse(buffered);
                } catch {
                    onInvalid();
                }
                if (record(frame)) {
                    onFrame(frame);
                } else if (frame !== undefined) {
                    onInvalid();
                }
            }
            buffered = '';
            skipping = false;
            start = newline + 1;
        }
    };
}

async function waitGate(gate, signal) {
    if (signal?.aborted) {
        return false;
    }
    if (signal === undefined) {
        await gate;
        return true;
    }
    let abort;
    const cancelled = new Promise((resolve) => {
        abort = () => resolve(false);
        signal.addEventListener('abort', abort, { once: true });
    });
    try {
        return await Promise.race([gate.then(() => true), cancelled]);
    } finally {
        signal.removeEventListener('abort', abort);
    }
}

/** Preserve the official public subprocess seam while guarding active turns.
 * Closing a private idle app-server is allowed. An active turn must first have
 * a matching native terminal, a definite start rejection, or observed range exit.
 * Broken pipes and unknown RPC failures never establish quiescence.
 */
export function guardCodexProcess(child) {
    if (!child.stdin || !child.stdout) {
        throw new Error('BRIDGE_DEEPSEEK_CODEX_PIPES_REQUIRED');
    }
    let startId;
    let threadId;
    let turnId;
    let startAccepted = false;
    let earlyTerminal;
    let submitted = false;
    let terminal = false;
    let uncertain = false;
    let stopWanted = false;
    let interruptSent = false;
    let terminateWanted = false;
    let terminated = false;
    let processExited = false;
    let rangeExited = false;
    let finalCallback;
    let gate = Promise.withResolvers();
    const interruptId = `bridge_cancel_${randomUUID().replaceAll('-', '')}`;
    const safe = () => processExited || rangeExited || (!uncertain && (!submitted || terminal));

    const forwardDisposal = () => {
        if (!safe()) {
            return;
        }
        gate.resolve();
        if (finalCallback) {
            const callback = finalCallback;
            finalCallback = undefined;
            child.stdin.end(callback);
        }
        if (terminateWanted && !terminated && !rangeExited) {
            terminated = true;
            child.terminate();
        }
    };
    const interrupt = () => {
        if (!stopWanted || !submitted || terminal || !threadId || !turnId || interruptSent || rangeExited) {
            return;
        }
        interruptSent = true;
        // The locked SDK ignores response ids absent from its pending map. This
        // separate namespace cannot collide with its req_<UUID> request ids.
        try {
            child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id: interruptId,
                method: 'turn/interrupt', params: { threadId, turnId } })}\n`, () => {});
        } catch {
            // Failed interruption leaves the gate held until terminal/range exit.
        }
    };
    const stop = () => {
        stopWanted = true;
        interrupt();
        forwardDisposal();
    };
    const bindTurn = (id, authoritative = false) => {
        if (!identity(id)) {
            uncertain = true;
            return false;
        }
        if (turnId !== undefined && turnId !== id) {
            if (authoritative) {
                uncertain = true;
            }
            return false;
        }
        turnId = id;
        interrupt();
        return true;
    };
    const outgoing = frames((frame) => {
        if (frame.method === 'turn/start') {
            if (submitted || !rpcId(frame.id) || !record(frame.params) || !identity(frame.params.threadId)) {
                uncertain = true;
                return;
            }
            submitted = true;
            gate = Promise.withResolvers();
            startId = frame.id;
            threadId = frame.params.threadId;
        } else if (frame.method === 'turn/interrupt' && record(frame.params)
            && frame.params.threadId === threadId && frame.params.turnId === turnId && turnId) {
            interruptSent = true;
        }
    }, () => { uncertain = true; });
    const incoming = frames((frame) => {
        if (!submitted || terminal) {
            return;
        }
        if (frame.id === startId && frame.method === undefined) {
            if (record(frame.result) && record(frame.result.turn)) {
                startAccepted = bindTurn(frame.result.turn.id, true);
                if (startAccepted && earlyTerminal === turnId) {
                    terminal = true;
                }
            } else if (record(frame.error) && START_REJECTION_CODES.has(frame.error.code)
                && turnId === undefined && !uncertain) {
                terminal = true;
            } else {
                uncertain = true;
            }
        } else if (record(frame.params) && frame.params.threadId === threadId) {
            if (frame.method === 'turn/started' && record(frame.params.turn)) {
                bindTurn(frame.params.turn.id);
            } else if (frame.method === 'item/completed') {
                bindTurn(frame.params.turnId);
            } else if (frame.method === 'turn/completed' && record(frame.params.turn)
                && TERMINAL_STATUSES.has(frame.params.turn.status) && bindTurn(frame.params.turn.id)) {
                earlyTerminal = turnId;
                terminal = startAccepted;
            }
        }
        forwardDisposal();
    }, () => { uncertain = true; });

    const stdin = new Writable({
        write(chunk, encoding, callback) {
            outgoing(chunk);
            try {
                child.stdin.write(chunk, encoding, callback);
            } catch (error) {
                callback(error);
            }
            interrupt();
        },
        final(callback) {
            finalCallback = callback;
            stop();
        },
    });
    // Independent raw-stream observation survives official wire.close(). Keeping
    // this reader attached also avoids backpressure when its reader is detached.
    const onStdoutError = () => {};
    const onStdinError = (error) => { stdin.destroy(error); };
    stdin.on('error', () => {});
    child.stdout.on('data', incoming);
    child.stdout.on('error', onStdoutError);
    child.stdin.on('error', onStdinError);
    const observeExit = async () => {
        try {
            if (await child.waitForExit() !== true) {
                return;
            }
            rangeExited = true;
            forwardDisposal();
            child.stdout.off('data', incoming);
            child.stdout.off('error', onStdoutError);
            child.stdin.off('error', onStdinError);
        } catch {
            // A failed owner observation does not prove that running work ended.
        }
    };
    void child.done.then(() => {
        // A dead app-server cannot finish active work. Disposal may now terminate
        // surviving descendants; waiting for an empty range before signalling it
        // would deadlock failure cleanup. The returned wait still proves exit.
        processExited = true;
        forwardDisposal();
        return observeExit();
    }, observeExit);
    return {
        stdin, stdout: child.stdout, stderr: child.stderr, control: child.control,
        collected: child.collected, done: child.done,
        terminate() {
            terminateWanted = true;
            stop();
        },
        async waitForExit(signal) {
            if (!safe() && !await waitGate(gate.promise, signal)) {
                return false;
            }
            return child.waitForExit(signal);
        },
    };
}
