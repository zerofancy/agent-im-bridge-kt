// Only public credential descriptions cross this boundary; no record payload.
export async function describeAuthentication(provider, credentials, { credentialRef, credentialKey }) {
    if (provider === 'deepseek-official') {
        const info = await credentials.describe(credentialRef('DEEPSEEK_API_KEY'));
        return {
            configured: info.configured,
            ...(info.source === undefined ? {} : { source: info.source }),
            writable: info.writable,
        };
    }
    if (provider === 'openai-codex') {
        const info = await credentials.describeRecord(credentialKey('llm-pi-ai', 'openai-codex'));
        return {
            configured: info.configured,
            ...(info.kind === undefined ? {} : { kind: info.kind }),
            writable: info.writable,
        };
    }
    throw new Error('BRIDGE_DEEPSEEK_UNSUPPORTED_AUTH_PROVIDER');
}
