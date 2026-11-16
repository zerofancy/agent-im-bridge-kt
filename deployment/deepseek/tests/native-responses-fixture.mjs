// Minimal loopback Responses protocol used by the locked Codex 0.153.4 tests.
// Based on upstream subagent-codex/tests/responses-fixture.ts; never uses an account.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';

function response(output) {
    return {
        id: 'resp_native_fixture', object: 'response', created_at: 1, status: 'completed',
        background: false, error: null, incomplete_details: null, instructions: null,
        max_output_tokens: null, max_tool_calls: null, model: 'fixture-model', output,
        parallel_tool_calls: true, previous_response_id: null, prompt_cache_key: null,
        prompt_cache_retention: null, reasoning: { effort: null, summary: null },
        safety_identifier: null, service_tier: 'default', store: false, temperature: null,
        text: { format: { type: 'text' }, verbosity: 'medium' }, tool_choice: 'auto', tools: [],
        top_logprobs: 0, top_p: null, truncation: 'disabled', user: null, metadata: {},
        usage: { input_tokens: 10, input_tokens_details: { cached_tokens: 0 },
            output_tokens: 1, output_tokens_details: { reasoning_tokens: 0 }, total_tokens: 11 },
    };
}

function events(text, call) {
    const item = call === undefined ? {
        id: 'msg_fixture', type: 'message', status: 'completed', role: 'assistant',
        content: [{ type: 'output_text', annotations: [], logprobs: [], text }],
    } : {
        id: 'fc_fixture', type: 'function_call', status: 'completed', name: call.name,
        arguments: JSON.stringify(call.arguments), call_id: 'call_native_fixture',
    };
    const completed = response([item]);
    const middle = call === undefined ? [
        { type: 'response.content_part.added', item_id: item.id, output_index: 0,
            content_index: 0, part: { ...item.content[0], text: '' } },
        { type: 'response.output_text.delta', item_id: item.id, output_index: 0,
            content_index: 0, delta: text, logprobs: [] },
        { type: 'response.output_text.done', item_id: item.id, output_index: 0,
            content_index: 0, text, logprobs: [] },
        { type: 'response.content_part.done', item_id: item.id, output_index: 0,
            content_index: 0, part: item.content[0] },
    ] : [
        { type: 'response.function_call_arguments.delta', item_id: item.id,
            output_index: 0, delta: item.arguments },
        { type: 'response.function_call_arguments.done', item_id: item.id,
            output_index: 0, arguments: item.arguments },
    ];
    return [
        { type: 'response.created', response: { ...completed, status: 'in_progress', output: [] } },
        { type: 'response.output_item.added', output_index: 0,
            item: { ...item, status: 'in_progress', ...(call === undefined ? { content: [] } : { arguments: '' }) } },
        ...middle, { type: 'response.output_item.done', output_index: 0, item },
        { type: 'response.completed', response: completed },
    ];
}

function finish(response, text, call) {
    for (const event of events(text, call)) {
        response.write(`data: ${JSON.stringify(event)}\n\n`);
    }
    response.end('data: [DONE]\n\n');
}

export async function startResponsesFixture() {
    const pending = [];
    const holds = new Map();
    const requests = [];
    const server = createServer(async (request, outgoing) => {
        try {
            let raw = '';
            for await (const chunk of request) {
                raw += chunk.toString('utf8');
                assert.ok(raw.length < 2 * 1024 * 1024);
            }
            const body = JSON.parse(raw);
            assert.equal(request.method, 'POST');
            assert.equal(request.url, '/v1/responses');
            assert.equal(body.model, 'fixture-model');
            assert.equal(request.headers.authorization, undefined);
            requests.push(body);
            outgoing.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
            outgoing.flushHeaders();
            const item = pending.shift();
            assert.ok(item, 'Unexpected native model request');
            if (item.hold !== undefined) {
                holds.set(item.hold, outgoing);
                item.started.resolve();
                return;
            }
            const names = new Set((body.tools ?? []).map((tool) => tool.name));
            const call = item.choices?.find((candidate) => names.has(candidate.name));
            assert.ok(item.choices === undefined || call, 'No advertised shell fixture tool');
            finish(outgoing, item.text ?? '', call);
        } catch {
            outgoing.destroy(new Error('Native Responses fixture protocol mismatch'));
        }
    });
    await new Promise((resolve, reject) => {
        server.once('error', reject);
        server.listen(0, '127.0.0.1', resolve);
    });
    return {
        baseUrl: `http://127.0.0.1:${server.address().port}/v1`, requests,
        enqueue(...items) { pending.push(...items); },
        hold(name) {
            const started = Promise.withResolvers();
            pending.push({ hold: name, started });
            return started.promise;
        },
        finish(name, text) {
            const outgoing = holds.get(name);
            assert.ok(outgoing && !outgoing.destroyed, 'Held sibling must still be connected');
            finish(outgoing, text);
        },
        async close() {
            server.closeAllConnections();
            await new Promise((resolve) => server.close(resolve));
        },
    };
}
