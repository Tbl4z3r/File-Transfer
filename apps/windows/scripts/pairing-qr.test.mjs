import assert from "node:assert/strict";
import test from "node:test";
import { ui, fixture, payload } from "./ui-harness.mjs";
import {
  encodePairingQr,
  MODULE_PIXELS,
  QUIET_ZONE_MODULES,
} from "../dist/pairing-qr.js";
test("fixture QR has natural integer scaling, quiet zone and valid GIF dimensions", () => {
  const { matrix, dataUrl, pixels } = encodePairingQr(payload);
  assert.equal(QUIET_ZONE_MODULES, 4);
  assert.equal(MODULE_PIXELS, 6);
  assert.equal(pixels, (matrix.length + 8) * 6);
  assert.ok(
    matrix.length >= 21 && matrix.every((row) => row.length === matrix.length),
  );
  const gif = Buffer.from(dataUrl.split(",")[1], "base64");
  assert.equal(gif.subarray(0, 6).toString(), "GIF87a");
  assert.equal(gif.readUInt16LE(6), pixels);
  assert.equal(gif.readUInt16LE(8), pixels);
});

for (const exit of ["hide", "expiry", "stop"])
  test(`clears descriptor and QR on ${exit}`, async () => {
    const h = await ui();
    await h.open();
    assert.equal(h.element("descriptor").value, payload);
    assert.match(h.element("pair-qr").src, /^data:image\/gif;base64,/);
    if (exit === "expiry") await h.expire();
    else {
      h.element(exit === "hide" ? "hide-pair" : "stop-session").onclick();
      await h.settle();
    }
    assert.equal(h.element("descriptor").value, "");
    assert.equal(h.element("pair-qr").src, "");
    assert.equal(h.element("pair-panel").open, false);
  });

test("encoder failure removes old QR and retains only current manual fallback", async () => {
  let calls = 0;
  const h = await ui((text) => {
    if (++calls === 2) throw new Error("synthetic encoder failure");
    return encodePairingQr(text);
  });
  await h.open();
  await h.open();
  assert.equal(h.element("pair-qr").src, "");
  assert.equal(h.element("descriptor").value, payload);
  assert.equal(h.element("qr-error").classList.contains("hidden"), false);
});

test("hiding during IPC prevents an obsolete response from redisplaying credentials", async () => {
  const h = await ui();
  const resolve = h.defer();
  h.element("pair").onclick();
  h.element("hide-pair").onclick();
  resolve(fixture.messages.descriptor);
  await h.settle();
  assert.equal(h.element("descriptor").value, "");
  assert.equal(h.element("pair-qr").src, "");
});

test("a failed new pairing invocation clears the previous credentials", async () => {
  const h = await ui();
  await h.open();
  h.failOpen();
  await h.open();
  assert.equal(h.element("descriptor").value, "");
  assert.equal(h.element("pair-qr").src, "");
  assert.equal(h.element("pair-panel").open, false);
});

test("a response delayed beyond the pairing window never displays credentials", async () => {
  const h = await ui();
  const resolve = h.defer();
  h.element("pair").onclick();
  await h.expire();
  resolve(fixture.messages.descriptor);
  await h.settle();
  assert.equal(h.element("descriptor").value, "");
  assert.equal(h.element("pair-qr").src, "");
});

for (const exit of ["escape", "native-close", "pagehide", "session-closed"])
  test(`clears modal QR credentials on ${exit}`, async () => {
    const h = await ui();
    await h.open();
    assert.equal(h.element("pair-panel").open, true);
    if (exit === "escape") h.element("pair-panel").dispatch("cancel");
    if (exit === "native-close") h.element("pair-panel").close();
    if (exit === "pagehide") h.pagehide();
    if (exit === "session-closed") {
      h.snapshot.session = null;
      await h.tick();
    }
    assert.equal(h.element("descriptor").value, "");
    assert.equal(h.element("pair-qr").src, "");
    assert.equal(h.element("pair-panel").open, false);
  });
