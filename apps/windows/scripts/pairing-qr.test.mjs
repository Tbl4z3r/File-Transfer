import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { encodePairingQr, MODULE_PIXELS, QUIET_ZONE_MODULES } from '../dist/pairing-qr.js';
const fixture = JSON.parse(await readFile(new URL('../../../protocol/fixtures/h1-vectors.json', import.meta.url), 'utf8'));
const payload = JSON.stringify(fixture.messages.descriptor);

test('fixture QR has natural integer scaling, quiet zone and valid GIF dimensions', () => {
  const { matrix, dataUrl, pixels } = encodePairingQr(payload);
  assert.equal(QUIET_ZONE_MODULES, 4);
  assert.equal(MODULE_PIXELS, 6);
  assert.equal(pixels, (matrix.length + 8) * 6);
  assert.ok(matrix.length >= 21 && matrix.every(row => row.length === matrix.length));
  const gif = Buffer.from(dataUrl.split(',')[1], 'base64');
  assert.equal(gif.subarray(0, 6).toString(), 'GIF87a');
  assert.equal(gif.readUInt16LE(6), pixels);
  assert.equal(gif.readUInt16LE(8), pixels);
});

async function ui(encoder = encodePairingQr) {
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) {
      const classes = new Set();
      elements.set(id, { value: '', textContent: '', src: '', classList: {
        add: value => classes.add(value), remove: value => classes.delete(value),
        contains: value => classes.has(value), toggle: (value, enabled) => enabled ? classes.add(value) : classes.delete(value)
      }, removeAttribute: name => { elements.get(id)[name] = ''; },
      replaceChildren() {}, append() {}, querySelector: () => true, scrollIntoView() {}, select() {} });
    }
    return elements.get(id);
  };
  let now = 0, interval, pending, fail = false;
  const snapshot = { session: { host: '192.168.1.20', port: 51337 }, receive_enabled: false, peers: [], transfers: [] };
  const context = { document: { getElementById: element, createElement: () => element(Symbol()) },
    window: { __TAURI__: { core: { invoke: async command => {
      if (command === 'snapshot') return snapshot;
      if (command === 'open_pairing_window') { if (fail) throw new Error('synthetic IPC failure'); return pending ? pending : fixture.messages.descriptor; }
      if (command === 'stop_session') snapshot.session = null;
    } } } }, encodePairingQr: encoder, performance: { now: () => now },
    setInterval: callback => { interval = callback; }, console, navigator: { clipboard: { writeText: async () => {} } } };
  const script = (await readFile(new URL('../dist/main.js', import.meta.url), 'utf8')).replace(/^import .*?;\n/, '');
  vm.runInNewContext(script, context);
  const settle = () => new Promise(resolve => setImmediate(resolve));
  await settle();
  return { element, settle, open: async () => { element('pair').onclick(); await settle(); },
    expire: async () => { now = 120001; interval(); await settle(); },
    failOpen: () => { fail = true; },
    defer: () => { let resolve; pending = new Promise(r => { resolve = r; }); return resolve; } };
}

for (const exit of ['hide', 'expiry', 'stop']) test(`clears descriptor and QR on ${exit}`, async () => {
  const h = await ui(); await h.open();
  assert.equal(h.element('descriptor').value, payload);
  assert.match(h.element('pair-qr').src, /^data:image\/gif;base64,/);
  if (exit === 'expiry') await h.expire();
  else { h.element(exit === 'hide' ? 'hide-pair' : 'stop-session').onclick(); await h.settle(); }
  assert.equal(h.element('descriptor').value, '');
  assert.equal(h.element('pair-qr').src, '');
  assert.ok(h.element('pair-panel').classList.contains('hidden'));
});

test('encoder failure removes old QR and retains only current manual fallback', async () => {
  let calls = 0;
  const h = await ui(text => { if (++calls === 2) throw new Error('synthetic encoder failure'); return encodePairingQr(text); });
  await h.open(); await h.open();
  assert.equal(h.element('pair-qr').src, '');
  assert.equal(h.element('descriptor').value, payload);
  assert.equal(h.element('qr-error').classList.contains('hidden'), false);
});

test('hiding during IPC prevents an obsolete response from redisplaying credentials', async () => {
  const h = await ui(); const resolve = h.defer();
  h.element('pair').onclick(); h.element('hide-pair').onclick();
  resolve(fixture.messages.descriptor); await h.settle();
  assert.equal(h.element('descriptor').value, '');
  assert.equal(h.element('pair-qr').src, '');
});

test('a failed new pairing invocation clears the previous credentials', async () => {
  const h = await ui(); await h.open(); h.failOpen(); await h.open();
  assert.equal(h.element('descriptor').value, '');
  assert.equal(h.element('pair-qr').src, '');
  assert.ok(h.element('pair-panel').classList.contains('hidden'));
});

test('a response delayed beyond the pairing window never displays credentials', async () => {
  const h = await ui(); const resolve = h.defer(); h.element('pair').onclick();
  await h.expire(); resolve(fixture.messages.descriptor); await h.settle();
  assert.equal(h.element('descriptor').value, '');
  assert.equal(h.element('pair-qr').src, '');
});
