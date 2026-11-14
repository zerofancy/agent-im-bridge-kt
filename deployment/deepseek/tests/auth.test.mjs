import assert from 'node:assert/strict';
import { test } from 'node:test';
import { describeAuthentication } from '../auth-core.mjs';

const references = {
    credentialRef: (name) => name,
    credentialKey: (scope, name) => `${scope}/${name}`,
};

test('DeepSeek authentication describes only its managed key reference', async () => {
    const result = await describeAuthentication('deepseek-official', {
        async describe(reference) {
            assert.equal(reference, 'DEEPSEEK_API_KEY');
            return { configured: true, source: 'file', writable: true, value: 'must not escape' };
        },
        readRecord() { throw new Error('must not read credential payload'); },
    }, references);
    assert.deepEqual(result, { configured: true, source: 'file', writable: true });
});

test('Codex subscription authentication describes a scoped OAuth record without reading it', async () => {
    const result = await describeAuthentication('openai-codex', {
        async describeRecord(key) {
            assert.equal(key, 'llm-pi-ai/openai-codex');
            return { configured: true, kind: 'grant', writable: true, payload: 'must not escape' };
        },
        describe() { throw new Error('must not fall back to an unrelated API key'); },
        readRecord() { throw new Error('must not read credential payload'); },
    }, references);
    assert.deepEqual(result, { configured: true, kind: 'grant', writable: true });
});

test('missing Codex login remains unconfigured and unsupported providers fail closed', async () => {
    const result = await describeAuthentication('openai-codex', {
        async describeRecord() { return { configured: false, writable: true }; },
    }, references);
    assert.deepEqual(result, { configured: false, writable: true });
    await assert.rejects(describeAuthentication('unknown', {}, references), /UNSUPPORTED_AUTH_PROVIDER/);
});
