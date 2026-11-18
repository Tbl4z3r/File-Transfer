import assert from "node:assert/strict";
import test from "node:test";
import { ui } from "./ui-harness.mjs";
const peers = [
  { id: "phone-a", name: "J4+" },
  { id: "phone-b", name: "Redmi Note 14" },
];
const transfer = (extra = {}) => ({
  transfer_id: "transfer-a",
  direction: "upload",
  file_name: "AY01.pdf",
  size_bytes: "5000000001",
  bytes_transferred: "0",
  state: "awaiting_acceptance",
  peer_id: "phone-a",
  destination: null,
  ...extra,
});
const commands = (h, command) =>
  h.calls.filter((call) => call.command === command);

test("session form starts a real session command and closing clears the session folder", async () => {
  const h = await ui(undefined, { session: null, peers });
  h.onInvoke(async (command, args) => {
    if (command === "start_session")
      h.snapshot.session = { session_id: "session-a", host: args.bindIpv4, port: 53123 };
    if (command === "choose_destination") return "C:\\Recepcion";
  });
  h.element("bind-ip").value = " 192.168.1.8 ";
  h.element("session-form").onsubmit({ preventDefault() {} });
  await h.settle();
  assert.equal(commands(h, "start_session")[0].args.bindIpv4, "192.168.1.8");
  assert.equal("port" in commands(h, "start_session")[0].args, false);
  assert.equal(h.element("session-address").textContent, "192.168.1.8:53123");
  assert.equal(h.element("session-setup").classList.contains("hidden"), true);
  h.element("choose-folder").click();
  await h.settle();
  assert.equal(h.element("folder-label").textContent, "C:\\Recepcion");
  assert.equal(h.element("choose-folder").getAttribute("title"), "C:\\Recepcion");
  h.element("stop-session").click();
  await h.settle();
  assert.equal(commands(h, "stop_session").length, 1);
  assert.equal(h.element("send-file").disabled, true);
  assert.equal(
    h.element("folder-label").textContent,
    "Se elige al aceptar cada archivo",
  );
});

test("send picks multiple real files and cancelled picker sends nothing", async () => {
  const h = await ui(undefined, { peers });
  let sources = null;
  h.onInvoke(async (command, args) => {
    if (command === "choose_sources") return sources;
    if (command === "offer_download") {
      const offered = transfer({ direction: "download", peer_id: args.peerId });
      h.snapshot.transfers = [offered];
      return offered;
    }
  });
  h.element("send-file").click();
  await h.settle();
  assert.equal(commands(h, "offer_download").length, 0);
  sources = ["C:\\Documentos\\AY01.pdf", "C:\\Documentos\\AY02.pdf"];
  h.element("device-choices").querySelectorAll("button")[1].click();
  h.element("send-file").click();
  await h.settle();
  assert.equal(commands(h, "choose_sources").length, 2);
  assert.equal(commands(h, "offer_download")[0].args.peerId, "phone-b");
  assert.equal(commands(h, "offer_download")[0].args.source, sources[0]);
  assert.equal(commands(h, "offer_download")[0].args.sessionId, "session-a");
  assert.match(h.element("batch-status").textContent, /Archivo 1 de 2 · 1 pendientes/);
  assert.equal(h.element("send-file").disabled, true);
});

test("receive toggle reflects backend state and recovers after a rejected action", async () => {
  const h = await ui(undefined, { peers });
  let reject = false;
  h.onInvoke(async (command, args) => {
    if (command === "set_receive_enabled") {
      if (reject) throw "io_error";
      h.snapshot.receive_enabled = args.enabled;
    }
  });
  h.element("receive").click();
  await h.settle();
  assert.equal(commands(h, "set_receive_enabled")[0].args.enabled, true);
  assert.equal(h.element("receive").getAttribute("aria-pressed"), "true");
  reject = true;
  h.element("receive").click();
  await h.settle();
  assert.equal(h.element("receive").getAttribute("aria-pressed"), "true");
  assert.equal(h.element("notice").classList.contains("error"), true);
});

test("incoming offer requires explicit acceptance; cancelled destination picker does not accept", async () => {
  const h = await ui(undefined, { peers, transfers: [transfer()] });
  let destination = null;
  h.onInvoke(async (command, args) => {
    if (command === "choose_destination") return destination;
    if (command === "accept_upload") {
      h.snapshot.transfers[0].state = "ready";
      h.snapshot.transfers[0].destination = args.destination;
    }
  });
  assert.equal(h.element("send-file").disabled, true);
  h.element("current-transfer")
    .querySelector('[data-transfer-action="accept"]')
    .click();
  await h.settle();
  assert.equal(commands(h, "accept_upload").length, 0);
  destination = "C:\\Recibidos";
  h.element("choose-folder").click();
  await h.settle();
  const accept = h
    .element("current-transfer")
    .querySelector('[data-transfer-action="accept"]');
  assert.equal(accept.textContent, "Aceptar");
  accept.click();
  await h.settle();
  assert.equal(commands(h, "accept_upload")[0].args.transferId, "transfer-a");
  assert.equal(commands(h, "accept_upload")[0].args.destination, destination);
  assert.equal(commands(h, "choose_destination").length, 2);
  assert.equal(
    h
      .element("current-transfer")
      .querySelector('[data-transfer-action="accept"]'),
    null,
  );
});

for (const state of [
  "awaiting_acceptance",
  "ready",
  "streaming",
  "awaiting_verification",
])
  test(`cancel/reject invokes cancellation for ${state}`, async () => {
    const h = await ui(undefined, { peers, transfers: [transfer({ state })] });
    h.onInvoke(async (command) => {
      if (command === "cancel_transfer")
        h.snapshot.transfers[0].state = "cancelled";
    });
    const cancel = h
      .element("current-transfer")
      .querySelector('[data-transfer-action="cancel"]');
    assert.equal(
      cancel.textContent,
      state === "awaiting_acceptance" ? "Rechazar" : "Cancelar",
    );
    cancel.click();
    await h.settle();
    assert.equal(
      commands(h, "cancel_transfer")[0].args.transferId,
      "transfer-a",
    );
    assert.equal(
      h
        .element("current-transfer")
        .querySelector('[data-transfer-action="cancel"]'),
      null,
    );
  });

test("progress preserves the cancel control and never treats a byte count as verified completion", async () => {
  const h = await ui(undefined, {
    peers,
    transfers: [
      transfer({ state: "streaming", bytes_transferred: "4000000000" }),
    ],
  });
  const cancel = h
    .element("current-transfer")
    .querySelector('[data-transfer-action="cancel"]');
  const progress = h.element("current-transfer").querySelector(".progress");
  cancel.focus();
  h.snapshot.transfers[0].bytes_transferred = "5000000001";
  await h.tick();
  assert.equal(
    h
      .element("current-transfer")
      .querySelector('[data-transfer-action="cancel"]'),
    cancel,
  );
  assert.equal(h.document.activeElement, cancel);
  assert.equal(progress.getAttribute("aria-valuenow"), "100");
  assert.equal(
    h.element("current-transfer").textContent.includes("verificado"),
    false,
  );
  h.snapshot.transfers[0].state = "awaiting_verification";
  await h.tick();
  assert.match(
    h.element("current-transfer").textContent,
    /Verificando integridad/,
  );
  h.snapshot.transfers[0].state = "completed";
  await h.tick();
  assert.match(
    h.element("current-transfer").textContent,
    /Recibido y verificado/,
  );
  assert.match(
    h.element("recent-transfer").textContent,
    /Recibido y verificado/,
  );
});

test("activity includes terminal results and untrusted names stay literal text", async () => {
  const hostile = "<img src=x onerror=alert(1)>";
  const h = await ui(undefined, {
    peers: [{ id: "phone-a", name: hostile }],
    transfers: [
      transfer({ state: "completed", file_name: hostile }),
      transfer({
        transfer_id: "transfer-b",
        state: "failed",
        error_code: "insufficient_space",
      }),
    ],
  });
  assert.equal(
    h.element("transfers").querySelectorAll(".transfer-card").length,
    2,
  );
  assert.match(h.element("transfers").textContent, /No hay espacio suficiente/);
  assert.equal(
    h.element("device-choices").querySelector(".peer-name").textContent,
    hostile,
  );
  assert.equal(
    h.element("transfers").querySelectorAll(".transfer-name")[1].textContent,
    hostile,
  );
  h.element("open-activity").click();
  assert.equal(h.element("activity-panel").open, true);
  assert.equal(h.element("open-activity").getAttribute("aria-current"), "page");
  h.element("activity-panel").close();
  assert.equal(h.element("open-activity").getAttribute("aria-current"), null);
});

test("revoking a paired device uses its id, removes it, and renders cancelled transfer", async () => {
  const h = await ui(undefined, {
    peers,
    transfers: [transfer({ state: "streaming" })],
  });
  h.onInvoke(async (command, args) => {
    if (command === "revoke_peer") {
      h.snapshot.peers = h.snapshot.peers.filter(
        (peer) => peer.id !== args.peerId,
      );
      h.snapshot.transfers[0].state = "cancelled";
      h.snapshot.transfers[0].error_code = "revoked";
    }
  });
  h.element("open-devices").click();
  h.element("peers").querySelector('[data-revoke="phone-a"]').click();
  await h.settle();
  assert.equal(commands(h, "revoke_peer")[0].args.peerId, "phone-a");
  assert.equal(
    h.element("device-choices").querySelectorAll("button").length,
    1,
  );
  assert.match(
    h.element("current-transfer").textContent,
    /Dispositivo revocado/,
  );
});

const deferred = () => {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
};
async function sendingBatch({ delayOffer = false } = {}) {
  const h = await ui(undefined, { peers });
  const gate = deferred();
  let serial = 0;
  h.onInvoke(async (command, args) => {
    if (command === "choose_sources") return ["C:\\uno.txt", "C:\\dos.txt", "C:\\tres.txt"];
    if (command === "offer_download") {
      const offered = transfer({
        transfer_id: `offer-${++serial}`, direction: "download", peer_id: args.peerId,
        file_name: args.source.split("\\").at(-1),
      });
      if (delayOffer) await gate.promise;
      h.snapshot.transfers.push(offered);
      return offered;
    }
    if (command === "cancel_transfer")
      h.snapshot.transfers.find((t) => t.transfer_id === args.transferId).state = "cancelled";
    if (command === "revoke_peer") {
      h.snapshot.peers = h.snapshot.peers.filter((p) => p.id !== args.peerId);
      for (const t of h.snapshot.transfers) if (t.peer_id === args.peerId && t.state !== "completed") t.state = "cancelled";
    }
  });
  h.element("send-file").click();
  await h.settle();
  return { h, gate };
}

test("batch advances only on completed, remains serial and targets the original peer", async () => {
  const { h } = await sendingBatch();
  h.element("device-choices").querySelectorAll("button")[1].click();
  for (const state of ["awaiting_acceptance", "ready", "streaming", "awaiting_verification"]) {
    h.snapshot.transfers[0].state = state;
    h.snapshot.transfers[0].bytes_transferred = h.snapshot.transfers[0].size_bytes;
    await h.tick();
    assert.equal(commands(h, "offer_download").length, 1);
  }
  h.snapshot.transfers[0].state = "completed";
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 2);
  assert.equal(commands(h, "offer_download")[1].args.peerId, "phone-a");
  assert.equal(commands(h, "offer_download")[1].args.source, "C:\\dos.txt");
  assert.match(h.element("batch-status").textContent, /Archivo 2 de 3 · 1 pendientes/);
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 2);
  h.snapshot.transfers[1].state = "completed";
  await h.tick();
  h.snapshot.transfers[2].state = "completed";
  await h.tick();
  assert.match(h.element("notice").textContent, /3 archivos enviados y verificados/);
  assert.equal(h.element("batch-panel").classList.contains("hidden"), true);
});

for (const state of ["failed", "cancelled"])
  test(`batch discards remaining files after ${state}`, async () => {
    const { h } = await sendingBatch();
    h.snapshot.transfers[0].state = state;
    await h.tick();
    await h.tick();
    assert.equal(commands(h, "offer_download").length, 1);
    assert.equal(h.element("batch-panel").classList.contains("hidden"), true);
    assert.match(h.element("notice").textContent, /pendientes no se enviarán/);
  });

test("cancel batch during active file cancels it and discards all pending files", async () => {
  const { h } = await sendingBatch();
  h.element("cancel-batch").click();
  await h.settle();
  assert.equal(commands(h, "cancel_transfer")[0].args.transferId, "offer-1");
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 1);
  assert.equal(h.element("batch-panel").classList.contains("hidden"), true);
});

test("batch can be cancelled in a gap while another v1 request occupies the server", async () => {
  const { h } = await sendingBatch();
  h.snapshot.transfers[0].state = "completed";
  h.snapshot.transfers.push(transfer({ transfer_id: "incoming" }));
  await h.tick();
  assert.match(h.element("batch-status").textContent, /Archivo 2 de 3/);
  assert.equal(commands(h, "offer_download").length, 1);
  h.element("cancel-batch").click();
  await h.settle();
  assert.equal(commands(h, "cancel_transfer").length, 0);
  h.snapshot.transfers[1].state = "completed";
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 1);
});

test("repeated polling never creates concurrent offers and cancellation cleans a late IPC offer", async () => {
  const { h, gate } = await sendingBatch({ delayOffer: true });
  await h.tick();
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 1);
  h.element("cancel-batch").click();
  await h.settle();
  assert.equal(h.element("send-file").disabled, true);
  gate.resolve();
  await h.settle();
  assert.equal(commands(h, "cancel_transfer")[0].args.transferId, "offer-1");
  assert.equal(h.snapshot.transfers[0].state, "cancelled");
  assert.equal(commands(h, "offer_download").length, 1);
  assert.equal(h.element("batch-panel").classList.contains("hidden"), true);
});

for (const event of ["stop", "revoke", "replacement-session"])
  test(`batch never resumes after ${event}`, async () => {
    const { h } = await sendingBatch();
    if (event === "stop") h.element("stop-session").click();
    if (event === "revoke") h.element("peers").querySelector('[data-revoke="phone-a"]').click();
    if (event === "replacement-session") h.snapshot.session.session_id = "session-b";
    await h.settle();
    await h.tick();
    h.snapshot.session = { ...h.snapshot.session, session_id: "session-c" };
    h.snapshot.transfers = [];
    await h.tick();
    assert.equal(commands(h, "offer_download").length, 1);
    assert.equal(h.element("batch-panel").classList.contains("hidden"), true);
  });

test("a stale snapshot and a concurrent receive action cannot discard a just-created offer", async () => {
  const { h, gate } = await sendingBatch({ delayOffer: true });
  const snapshotGate = deferred();
  h.onSnapshot(async (value) => { await snapshotGate.promise; return value; });
  await h.tick();
  gate.resolve();
  await h.settle();
  h.element("receive").click();
  await h.settle();
  assert.equal(h.element("batch-panel").classList.contains("hidden"), false);
  snapshotGate.resolve();
  await h.settle();
  h.onSnapshot(null);
  await h.tick();
  assert.equal(h.element("batch-panel").classList.contains("hidden"), false);
  assert.equal(commands(h, "offer_download").length, 1);
  h.snapshot.transfers[0].state = "completed";
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 2);
});
