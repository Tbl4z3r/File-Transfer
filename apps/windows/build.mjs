import { copyFile, mkdir } from 'node:fs/promises';
await mkdir(new URL('./dist/', import.meta.url), { recursive: true });
for (const name of ['index.html', 'style.css']) {
  await copyFile(new URL(`./src/${name}`, import.meta.url), new URL(`./dist/${name}`, import.meta.url));
}
// Serve the pinned browser ESM locally; tsc intentionally does not bundle imports.
await mkdir(new URL('./dist/vendor/', import.meta.url), { recursive: true });
await copyFile(new URL('./node_modules/qrcode-generator/dist/qrcode.mjs', import.meta.url), new URL('./dist/vendor/qrcode.mjs', import.meta.url));
await copyFile(new URL('./src/vendor/qrcode-LICENSE.txt', import.meta.url), new URL('./dist/vendor/qrcode-LICENSE.txt', import.meta.url));
