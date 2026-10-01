// Run with Node.js and sharp installed: node assets/branding/export-icon.cjs
// On macOS, also exports the multi-resolution ICNS using the system iconutil.
const fs = require('node:fs/promises');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const sharp = require('sharp');

async function main() {
    const source = path.join(__dirname, 'agent-bridge-app-icon.svg');
    const render = (size, output) => sharp(source, { density: 144 })
        .resize(size, size).png().toFile(output);
    await render(1024, path.join(__dirname, 'agent-bridge-app-icon.png'));

    if (process.platform === 'darwin') {
        const iconset = path.resolve(__dirname, '../../desktop-app/build/branding/AgentBridge.iconset');
        await fs.mkdir(iconset, { recursive: true });
        for (const size of [16, 32, 128, 256, 512]) {
            await render(size, path.join(iconset, `icon_${size}x${size}.png`));
            await render(size * 2, path.join(iconset, `icon_${size}x${size}@2x.png`));
        }
        execFileSync('/usr/bin/iconutil', ['-c', 'icns', iconset, '-o',
            path.join(__dirname, 'agent-bridge-app-icon.icns')]);
    }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
