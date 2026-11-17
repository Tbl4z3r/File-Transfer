// Only the public synthetic fixture is exported; never pass live credentials here.
import { readFile, writeFile } from 'node:fs/promises';
import { encodePairingQr } from '../dist/pairing-qr.js';
const fixture = JSON.parse(await readFile(new URL('../../../protocol/fixtures/h1-vectors.json', import.meta.url), 'utf8'));
const payload = JSON.stringify(fixture.messages.descriptor);
const qr = encodePairingQr(payload);
const output = process.argv[2];
if (!output) throw new Error('Uso: node scripts/export-pairing-qr.mjs /tmp/qr-fixture.gif');
await writeFile(output, Buffer.from(qr.dataUrl.split(',')[1], 'base64'));
await writeFile(`${output}.json`, payload);
console.log(`Fixture público exportado: ${qr.pixels} × ${qr.pixels}, ECC M, margen de 4 módulos`);
