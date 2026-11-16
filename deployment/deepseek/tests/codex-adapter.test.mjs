import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resolve } from 'node:path';
import { activateCodexProvider, apply, installCodexProvider } from '../bridge-codex.mjs';

const config = { mode: 'danger-full-access', codexHome: resolve('/isolated/codex'), model: 'gpt-6.1-sol' };

test('official public apply receives only fixed full access and explicit model/home', () => {
    const provider = {};
    let registered;
    const ctx = {
        logger: {}, subagents: { registerProvider(value) { registered = value; } }, subprocess: {},
    };
    installCodexProvider(ctx, config, (facade, options) => {
        assert.equal(facade.logger, ctx.logger);
        assert.deepEqual(options, {
            providerName: 'codex', model: 'gpt-6.1-sol',
            permissionMode: 'dangerously-bypass-approvals-and-sandbox',
            env: { CODEX_HOME: config.codexHome }, disposeGraceMs: 3000,
        });
        facade.subagents.registerProvider(provider);
    });
    assert.equal(registered, provider);
});

test('read-only and workspace-write cannot register the native provider', () => {
    for (const mode of ['read-only', 'workspace-write', undefined]) {
        assert.throws(() => installCodexProvider({}, { ...config, mode }, () => {
            throw new Error('must not reach the official plugin');
        }), /FULL_ACCESS_REQUIRED/u);
    }
});

test('native state and model must be explicit and valid before loading the provider', () => {
    for (const codexHome of ['relative', '', '/bad\nhome']) {
        assert.throws(() => installCodexProvider({}, { ...config, codexHome }), /HOME_REQUIRED/u);
    }
    for (const model of ['', ' ', 'bad\nmodel', 'x'.repeat(257)]) {
        assert.throws(() => installCodexProvider({}, { ...config, model }), /MODEL_REQUIRED/u);
    }
});

test('a future official spawn signal cannot bypass the gate through raw subprocess cancellation', () => {
    installCodexProvider({ logger: {}, subagents: {}, subprocess: {} }, config, (facade) => {
        assert.throws(() => facade.subprocess.spawn({ signal: new AbortController().signal }), /UNEXPECTED_SPAWN_SIGNAL/u);
    });
});

test('disabled readiness never imports or registers native Codex', async () => {
    const provided = [];
    const ctx = { provide: (...args) => provided.push(args) };
    await activateCodexProvider(ctx, { enabled: false }, () => {
        throw new Error('disabled provider must remain dormant');
    });
    assert.deepEqual(provided, [['bridgeDeepSeekCodex', { version: 1, enabled: false }]]);
    assert.equal(Object.isFrozen(provided[0][1]), true);
    await apply(ctx, { enabled: false });
});

test('enabled readiness follows validated official registration and failed registration stays unready', async () => {
    const events = [];
    const ctx = {
        logger: {}, subprocess: {}, subagents: { registerProvider() { events.push('registered'); } },
        provide: (name, value) => events.push([name, value]),
    };
    await activateCodexProvider(ctx, { ...config, enabled: true }, async () => ({
        apply(facade) { facade.subagents.registerProvider({}); },
    }));
    assert.deepEqual(events, ['registered', ['bridgeDeepSeekCodex', { version: 1, enabled: true }]]);
    events.splice(0);
    await assert.rejects(activateCodexProvider(ctx, { ...config, enabled: true }, async () => ({
        apply() { throw new Error('registration failed'); },
    })), /registration failed/u);
    assert.deepEqual(events, []);
    await assert.rejects(activateCodexProvider(ctx, { ...config, enabled: true, mode: 'read-only' }, () => {
        throw new Error('must validate before import');
    }), /FULL_ACCESS_REQUIRED/u);
    await assert.rejects(activateCodexProvider(ctx, {}, () => {}), /ENABLED_REQUIRED/u);
});
