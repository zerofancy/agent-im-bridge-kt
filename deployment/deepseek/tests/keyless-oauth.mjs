// Test-only OAuth protocol behind the real DSH authorization/credential flow.
// It uses a random loopback port, no accounts, and no external requests.
import { createServer } from 'node:http';

export const openaiCodexOAuth = {
    name: 'Keyless OAuth fixture',
    isSubscription: true,
    async login(interaction) {
        const method = await interaction.prompt({
            type: 'select', message: 'Select test login method',
            options: [{ id: 'browser', label: 'Browser' }],
        });
        if (method !== 'browser') {
            throw new Error('fixture did not select browser login');
        }
        const completion = Promise.withResolvers();
        const manual = new AbortController();
        const server = createServer((_request, response) => {
            response.end('Complete');
            completion.resolve();
        });
        await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
        try {
            const address = server.address();
            interaction.notify({ type: 'auth_url', url: `http://127.0.0.1:${address.port}/complete` });
            const input = interaction.prompt({ type: 'manual_code', message: 'Wait for test callback', signal: manual.signal });
            await Promise.race([completion.promise, input.then(() => { throw new Error('unexpected manual test input'); })]);
            return { type: 'oauth', access: 'synthetic-access', refresh: 'synthetic-refresh', expires: Date.now() + 3600000 };
        } finally {
            manual.abort();
            await new Promise((resolve) => server.close(resolve));
        }
    },
    async refresh(credential) { return credential; },
    async toAuth(credential) { return { apiKey: credential.access }; },
};
