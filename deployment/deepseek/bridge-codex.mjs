import { createRequire } from 'node:module';
import { isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';
import { guardCodexProcess } from './codex-lifecycle.mjs';

export const name = 'bridge-codex';
export const inject = ['subagents', 'subprocess'];

function validateConfig(config) {
    if (config?.mode !== 'danger-full-access') {
        throw new Error('BRIDGE_DEEPSEEK_CODEX_FULL_ACCESS_REQUIRED');
    }
    if (typeof config.codexHome !== 'string' || !isAbsolute(config.codexHome)
        || /[\x00-\x1f\x7f]/u.test(config.codexHome)) {
        throw new Error('BRIDGE_DEEPSEEK_CODEX_HOME_REQUIRED');
    }
    if (typeof config.model !== 'string' || !config.model.trim() || config.model.length > 256
        || /[\x00-\x1f\x7f]/u.test(config.model)) {
        throw new Error('BRIDGE_DEEPSEEK_CODEX_MODEL_REQUIRED');
    }
}

/** Public-service facade for the locked official provider. No vendor mutation. */
export function installCodexProvider(ctx, config, officialApply) {
    validateConfig(config);
    const facade = {
        logger: ctx.logger,
        subagents: { registerProvider: (provider) => ctx.subagents.registerProvider(provider) },
        subprocess: {
            spawn: (spec) => {
                if (spec.signal !== undefined) {
                    throw new Error('BRIDGE_DEEPSEEK_CODEX_UNEXPECTED_SPAWN_SIGNAL');
                }
                return guardCodexProcess(ctx.subprocess.spawn(spec));
            },
        },
    };
    officialApply(facade, {
        providerName: 'codex', model: config.model,
        permissionMode: 'dangerously-bypass-approvals-and-sandbox',
        env: { CODEX_HOME: config.codexHome }, disposeGraceMs: 3000,
    });
}

export async function activateCodexProvider(ctx, config, loadOfficial) {
    if (typeof config?.enabled !== 'boolean') {
        throw new Error('BRIDGE_DEEPSEEK_CODEX_ENABLED_REQUIRED');
    }
    if (config.enabled) {
        validateConfig(config);
        const official = await loadOfficial();
        installCodexProvider(ctx, config, official.apply);
    }
    ctx.provide('bridgeDeepSeekCodex', Object.freeze({ version: 1, enabled: config.enabled }));
}

export async function apply(ctx, config) {
    await activateCodexProvider(ctx, config, async () => {
        const require = createRequire(new URL('./runtime/package.json', import.meta.url));
        return import(pathToFileURL(require.resolve('@deepseek-ai/dsh-subagent-codex')).href);
    });
}
