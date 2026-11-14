import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { describeAuthentication } from './auth-core.mjs';

const require = createRequire(new URL('./runtime/package.json', import.meta.url));
const load = async (name) => import(pathToFileURL(require.resolve(name)).href);
const { Context } = await load('@deepseek-ai/cordis');
const { default: CredentialsLocal } = await load('@deepseek-ai/dsh-credentials-local');
const { credentialRef, credentialKey } = await load('@deepseek-ai/dsh-credentials');
const ctx = new Context();
const fiber = ctx.plugin(CredentialsLocal, { dshHome: process.env.DSH_HOME, watch: false });
try {
    await fiber;
    const info = await describeAuthentication(process.env.BRIDGE_DEEPSEEK_PROVIDER ?? 'deepseek-official',
        ctx.credentials, { credentialRef, credentialKey });
    process.stdout.write(JSON.stringify(info));
} finally {
    await fiber.dispose();
}
