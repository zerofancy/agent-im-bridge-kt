const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'bridge-favicon-'));
try {
    const sizes = [16, 32, 48, 256];
    const images = sizes.map(size => {
        const output = path.join(directory, `${size}.png`);
        execFileSync('sips', ['-z', String(size), String(size),
            path.join(__dirname, 'agent-bridge-app-icon.png'), '--out', output], { stdio: 'ignore' });
        return fs.readFileSync(output);
    });
    const header = Buffer.alloc(6 + sizes.length * 16);
    header.writeUInt16LE(1, 2);
    header.writeUInt16LE(sizes.length, 4);
    let offset = header.length;
    images.forEach((image, index) => {
        const entry = 6 + index * 16;
        header[entry] = sizes[index] % 256;
        header[entry + 1] = sizes[index] % 256;
        header.writeUInt16LE(1, entry + 4);
        header.writeUInt16LE(32, entry + 6);
        header.writeUInt32LE(image.length, entry + 8);
        header.writeUInt32LE(offset, entry + 12);
        offset += image.length;
    });
    fs.writeFileSync(path.join(__dirname, 'agent-bridge-app-icon.ico'), Buffer.concat([header, ...images]));
} finally {
    fs.rmSync(directory, { recursive: true, force: true });
}
