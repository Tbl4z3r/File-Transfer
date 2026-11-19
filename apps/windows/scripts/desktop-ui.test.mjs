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
      h.snapshot.session = {
        session_id: "session-a",
        host: args.bindIpv4,
        port: 53123,
      };
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
  assert.equal(
    h.element("choose-folder").getAttribute("title"),
    "C:\\Recepcion",
  );
  h.element("stop-session").click();
  await h.settle();
  assert.equal(commands(h, "stop_session").length, 1);
  assert.equal(h.element("send-file").disabled, false);
  assert.equal(
    h.element("folder-label").textContent,
    "Se elige al aceptar cada archivo",
  );
});

test("one click with no IP opens the QR and a pairing failure keeps the listener", async () => {
  for (const failQr of [false, true]) {
    const h = await ui(undefined, { session: null, peers: [] });
    if (failQr) h.failOpen();
    h.onInvoke(async (command) => {
      if (command === "start_session") h.snapshot.session = { session_id: "automatic", host: "192.168.1.8", port: 51337 };
    });
    h.element("session-form").onsubmit({ preventDefault() {} });
    await h.settle();
    assert.equal(commands(h, "start_session")[0].args.bindIpv4, "");
    assert.equal(commands(h, "open_pairing_window").length, 1);
    assert.equal(commands(h, "stop_session").length, 0);
    assert.equal(h.snapshot.session.session_id, "automatic");
    assert.equal(h.element("session-address").textContent, "192.168.1.8:51337");
    assert.equal(h.element("pair-panel").open, !failQr);
  }
});

test("automatic QR survives a snapshot that began before startup", async () => {
  const h = await ui(undefined, { session: null, peers: [] });
  let release;
  h.onSnapshot((value) => new Promise((resolve) => { release = () => resolve(value); }));
  await h.tick();
  h.onInvoke(async (command) => {
    if (command === "start_session") h.snapshot.session = { session_id: "new", host: "192.168.1.8", port: 51337 };
  });
  h.element("session-form").onsubmit({ preventDefault() {} });
  await h.settle();
  h.onSnapshot(null);
  release();
  await h.settle();
  await h.tick();
  assert.equal(h.element("pair-panel").open, true);
  assert.equal(commands(h, "open_pairing_window").length, 1);
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
  sources = ["AY01.pdf", "AY02.pdf"].map((file_name) => ({
    source: `C:\\Documentos\\${file_name}`,
    file_name,
    size_bytes: "1024",
  }));
  h.element("device-choices").querySelectorAll("button")[1].click();
  h.element("send-file").click();
  await h.settle();
  assert.equal(commands(h, "choose_sources").length, 2);
  assert.equal(commands(h, "offer_download")[0].args.peerId, "phone-b");
  assert.equal(commands(h, "offer_download")[0].args.source, sources[0].source);
  assert.equal(commands(h, "offer_download")[0].args.sessionId, "session-a");
  assert.match(
    h.element("batch-status").textContent,
    /Archivo 1 de 2 · 1 pendientes/,
  );
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
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
async function sendingBatch({ delayOffer = false } = {}) {
  const h = await ui(undefined, { peers });
  const gate = deferred();
  let serial = 0;
  h.onInvoke(async (command, args) => {
    if (command === "choose_sources")
      return ["uno.txt", "dos.txt", "tres.txt"].map((file_name) => ({
        source: `C:\\${file_name}`,
        file_name,
        size_bytes: "1024",
      }));
    if (command === "offer_download") {
      const offered = transfer({
        transfer_id: `offer-${++serial}`,
        direction: "download",
        peer_id: args.peerId,
        file_name: args.source.split("\\").at(-1),
      });
      if (delayOffer) await gate.promise;
      h.snapshot.transfers.push(offered);
      return offered;
    }
    if (command === "cancel_transfer")
      h.snapshot.transfers.find(
        (t) => t.transfer_id === args.transferId,
      ).state = "cancelled";
    if (command === "revoke_peer") {
      h.snapshot.peers = h.snapshot.peers.filter((p) => p.id !== args.peerId);
      for (const t of h.snapshot.transfers)
        if (t.peer_id === args.peerId && t.state !== "completed")
          t.state = "cancelled";
    }
  });
  h.element("send-file").click();
  await h.settle();
  return { h, gate };
}

test("batch advances only on completed, remains serial and targets the original peer", async () => {
  const { h } = await sendingBatch();
  h.element("device-choices").querySelectorAll("button")[1].click();
  for (const state of [
    "awaiting_acceptance",
    "ready",
    "streaming",
    "awaiting_verification",
  ]) {
    h.snapshot.transfers[0].state = state;
    h.snapshot.transfers[0].bytes_transferred =
      h.snapshot.transfers[0].size_bytes;
    await h.tick();
    assert.equal(commands(h, "offer_download").length, 1);
  }
  h.snapshot.transfers[0].state = "completed";
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 2);
  assert.equal(commands(h, "offer_download")[1].args.peerId, "phone-a");
  assert.equal(commands(h, "offer_download")[1].args.source, "C:\\dos.txt");
  assert.match(
    h.element("batch-status").textContent,
    /Archivo 2 de 3 · 1 pendientes/,
  );
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 2);
  h.snapshot.transfers[1].state = "completed";
  await h.tick();
  h.snapshot.transfers[2].state = "completed";
  await h.tick();
  assert.match(
    h.element("notice").textContent,
    /3 archivos enviados y verificados/,
  );
  assert.equal(h.element("batch-panel").classList.contains("hidden"), false);
});

for (const state of ["failed", "cancelled"])
  test(`batch discards remaining files after ${state}`, async () => {
    const { h } = await sendingBatch();
    h.snapshot.transfers[0].state = state;
    await h.tick();
    await h.tick();
    assert.equal(commands(h, "offer_download").length, 1);
    assert.equal(h.element("batch-panel").classList.contains("hidden"), false);
    assert.match(h.element("notice").textContent, /pendientes no se enviarán/);
  });

test("cancel batch during active file cancels it and discards all pending files", async () => {
  const { h } = await sendingBatch();
  h.element("cancel-batch").click();
  await h.settle();
  assert.equal(commands(h, "cancel_transfer")[0].args.transferId, "offer-1");
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 1);
  assert.equal(h.element("batch-panel").classList.contains("hidden"), false);
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
  assert.equal(h.element("batch-panel").classList.contains("hidden"), false);
});

for (const event of ["stop", "revoke", "replacement-session"])
  test(`batch never resumes after ${event}`, async () => {
    const { h } = await sendingBatch();
    if (event === "stop") h.element("stop-session").click();
    if (event === "revoke")
      h.element("peers").querySelector('[data-revoke="phone-a"]').click();
    if (event === "replacement-session")
      h.snapshot.session.session_id = "session-b";
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
  h.onSnapshot(async (value) => {
    await snapshotGate.promise;
    return value;
  });
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

const uploadBatch = (extra = {}) => ({
  batch_id: "batch-a",
  peer_id: "phone-a",
  state: "awaiting_acceptance",
  files: ["<img src=x onerror=alert(1)>.txt", "dos.txt"].map((file_name) => ({
    file_name,
    size_bytes: "1024",
    transfer_id: null,
    state: "pending",
  })),
  ...extra,
});

test("incoming manifest stays literal, retains rows and scroll, and reserves send before a transfer exists", async () => {
  const h = await ui(undefined, { peers, upload_batch: uploadBatch() });
  const list = h.element("batch-files");
  const first = list.children[0];
  assert.equal(
    list.querySelector(".batch-file-name").textContent,
    "<img src=x onerror=alert(1)>.txt",
  );
  assert.match(list.textContent, /1 kB.*Pendiente de aceptación/);
  assert.equal(h.element("send-file").disabled, true);
  assert.equal(h.element("accept-batch").hidden, false);
  assert.equal(h.element("cancel-batch").textContent, "Rechazar lote");
  list.scrollTop = 90;
  await h.tick();
  assert.equal(list.children[0], first);
  assert.equal(list.scrollTop, 90);
  h.snapshot.session.session_id = "session-b";
  h.snapshot.upload_batch = null;
  await h.tick();
  assert.equal(h.element("batch-panel").classList.contains("hidden"), true);
});

test("one incoming approval chooses its folder once and keeps verified and unsent results", async () => {
  const h = await ui(undefined, { peers, upload_batch: uploadBatch() });
  let destination = null;
  h.onInvoke(async (command, args) => {
    if (command === "choose_destination") return destination;
    if (command === "accept_upload_batch") {
      h.snapshot.upload_batch.state = "accepted";
      return { batch_id: args.batchId, state: "accepted" };
    }
    if (command === "cancel_upload_batch")
      h.snapshot.upload_batch.state = "cancelled";
  });
  h.element("accept-batch").click();
  await h.settle();
  assert.equal(commands(h, "accept_upload_batch").length, 0);
  destination = "C:\\Recibidos";
  h.element("accept-batch").click();
  await h.settle();
  assert.deepEqual(
    JSON.parse(JSON.stringify(commands(h, "accept_upload_batch")[0].args)),
    { batchId: "batch-a", destination },
  );
  assert.equal(h.element("accept-batch").hidden, true);
  h.snapshot.upload_batch.files[0] = {
    ...h.snapshot.upload_batch.files[0],
    transfer_id: "incoming-a",
    state: "streaming",
  };
  h.snapshot.transfers = [
    transfer({ transfer_id: "incoming-a", state: "streaming" }),
  ];
  await h.tick();
  assert.match(h.element("batch-files").textContent, /Recibiendo/);
  assert.equal(
    h
      .element("current-transfer")
      .querySelector('[data-transfer-action="accept"]'),
    null,
  );
  h.snapshot.upload_batch.files[0].state = "completed";
  h.snapshot.transfers[0].state = "completed";
  h.element("cancel-batch").click();
  await h.settle();
  assert.equal(commands(h, "cancel_upload_batch")[0].args.batchId, "batch-a");
  assert.equal(commands(h, "choose_destination").length, 2);
  assert.match(
    h.element("batch-files").textContent,
    /Recibido y verificado.*Sin enviar/,
  );
  assert.equal(h.element("batch-panel").classList.contains("hidden"), false);
  assert.equal(h.element("cancel-batch").hidden, true);
  h.snapshot.upload_batch = null;
  await h.tick();
  assert.match(
    h.element("batch-files").textContent,
    /Recibido y verificado.*Sin enviar/,
  );
});

test("reject incoming lot does not choose a folder or accept individual files", async () => {
  const h = await ui(undefined, { peers, upload_batch: uploadBatch() });
  h.onInvoke(async (command) => {
    if (command === "cancel_upload_batch")
      h.snapshot.upload_batch.state = "cancelled";
  });
  h.element("cancel-batch").click();
  await h.settle();
  assert.equal(commands(h, "cancel_upload_batch").length, 1);
  assert.equal(commands(h, "choose_destination").length, 0);
  assert.equal(commands(h, "accept_upload").length, 0);
  assert.match(h.element("batch-files").textContent, /Sin enviar/);
});

test("a folder reply cannot accept a batch replaced while the picker was open", async () => {
  const h = await ui(undefined, { peers, upload_batch: uploadBatch() });
  const gate = deferred();
  h.onInvoke(async (command) => {
    if (command === "choose_destination") return gate.promise;
  });
  h.element("accept-batch").click();
  await h.settle();
  assert.equal(h.element("accept-batch").disabled, true);
  h.snapshot.upload_batch = uploadBatch({ batch_id: "batch-b" });
  await h.tick();
  gate.resolve("C:\\Recibidos");
  await h.settle();
  assert.equal(commands(h, "accept_upload_batch").length, 0);
});

test("outbound rows show native selected metadata, then actual offered metadata, and keep unsent files", async () => {
  const { h, gate } = await sendingBatch({ delayOffer: true });
  const list = h.element("batch-files");
  assert.equal(list.children.length, 3);
  assert.match(list.textContent, /uno.txt1 kB.*dos.txt1 kB/);
  assert.equal(list.textContent.includes("C:\\"), false);
  gate.resolve();
  await h.settle();
  await h.tick();
  assert.match(list.textContent, /uno.txt5 GB/);
  h.snapshot.transfers[0].state = "failed";
  await h.tick();
  assert.match(
    list.textContent,
    /Interrumpido.*dos.txt1 kB · Sin enviar.*tres.txt1 kB · Sin enviar/,
  );
  assert.equal(h.element("cancel-batch").hidden, true);
});

test("an inbound reservation arriving during source selection prevents an outgoing offer", async () => {
  const h = await ui(undefined, { peers });
  const gate = deferred();
  h.onInvoke(async (command) => {
    if (command === "choose_sources") return gate.promise;
  });
  h.element("send-file").click();
  await h.settle();
  h.snapshot.upload_batch = uploadBatch();
  await h.tick();
  gate.resolve([
    { source: "C:\\uno.txt", file_name: "uno.txt", size_bytes: "1" },
  ]);
  await h.settle();
  assert.equal(commands(h, "offer_download").length, 0);
});

test("an outgoing queue resumes visibly after a batch arriving between its files finishes", async () => {
  const { h } = await sendingBatch();
  h.snapshot.transfers[0].state = "completed";
  h.snapshot.upload_batch = uploadBatch();
  await h.tick();
  assert.match(h.element("batch-status").textContent, /Solicitud entrante/);
  assert.equal(commands(h, "offer_download").length, 1);
  h.snapshot.upload_batch.state = "cancelled";
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 2);
  assert.match(h.element("batch-status").textContent, /Archivo 2 de 3/);
  await h.tick();
  assert.match(h.element("batch-status").textContent, /Archivo 2 de 3/);
  assert.match(
    h.element("batch-files").textContent,
    /uno.txt.*Enviado y verificado.*tres.txt/,
  );
});

const inboxPeers = peers.map((peer) => ({ ...peer, supports_inbox: true }));
const sources = ["uno.txt", "dos.txt"].map((file_name) => ({
  source: `C:\\${file_name}`, file_name, size_bytes: "1024",
}));
const downloadBatch = (extra = {}) => uploadBatch({
  batch_id: "download-a",
  files: sources.map(({ file_name, size_bytes }) => ({
    file_name, size_bytes, transfer_id: null, state: "pending",
  })),
  ...extra,
});
async function sendingInboxBatch() {
  const h = await ui(undefined, { peers: inboxPeers });
  h.onInvoke(async (command) => {
    if (command === "choose_sources") return sources;
    if (command === "offer_download_batch") {
      h.snapshot.download_batch = downloadBatch();
      return { batch_id: "download-a", state: "awaiting_acceptance" };
    }
    if (command === "cancel_download_batch") {
      h.snapshot.download_batch.state = "cancelled";
      return { batch_id: "download-a", state: "cancelled" };
    }
  });
  h.element("send-file").click();
  await h.settle();
  return h;
}

test("inbox peer receives one batch offer and never an individual or local acceptance", async () => {
  const h = await sendingInboxBatch();
  for (let i = 0; i < 3; i++) await h.tick();
  h.element("send-file").click();
  // Hidden buttons cannot be clicked in the browser; inspect their state here.
  assert.equal(h.element("accept-batch").hidden, true);
  assert.equal(commands(h, "offer_download_batch").length, 1);
  assert.deepEqual(JSON.parse(JSON.stringify(commands(h, "offer_download_batch")[0].args)), {
    peerId: "phone-a", sources: sources.map((source) => source.source), sessionId: "session-a",
  });
  assert.equal(commands(h, "offer_download").length, 0);
  assert.equal(commands(h, "accept_upload_batch").length, 0);
  assert.equal(commands(h, "choose_destination").length, 0);
  assert.equal(h.element("cancel-batch").textContent, "Cancelar lote");
  assert.match(h.element("batch-status").textContent, /Esperando aceptación en el móvil/);
  assert.equal(h.element("send-file").disabled, true);
  h.element("cancel-batch").click();
  await h.settle();
  assert.deepEqual(JSON.parse(JSON.stringify(commands(h, "cancel_download_batch")[0].args)), {
    batchId: "download-a",
  });
  assert.equal(commands(h, "cancel_transfer").length, 0);
  assert.equal(h.element("cancel-batch").hidden, true);
  assert.match(h.element("batch-files").textContent, /Sin enviar/);
});

test("outbound batch snapshots own progress and reserve the slot between files", async () => {
  const h = await sendingInboxBatch();
  const list = h.element("batch-files");
  const first = list.children[0];
  list.scrollTop = 70;
  h.snapshot.download_batch.state = "accepted";
  h.snapshot.download_batch.files[0] = {
    file_name: "nombre-real.txt", size_bytes: "2048", transfer_id: "outgoing-a", state: "streaming",
  };
  h.snapshot.transfers = [transfer({
    transfer_id: "outgoing-a", direction: "download", state: "streaming",
  })];
  await h.tick();
  assert.equal(list.children[0], first);
  assert.equal(list.scrollTop, 70);
  assert.match(list.textContent, /nombre-real.txt2 kB · Enviando/);
  assert.equal(h.element("current-transfer").querySelector('[data-transfer-action="cancel"]'), null);
  h.snapshot.download_batch.files[0].state = "completed";
  h.snapshot.transfers = [];
  await h.tick();
  assert.equal(h.element("send-file").disabled, true);
  assert.equal(commands(h, "offer_download").length, 0);
  assert.equal(commands(h, "offer_download_batch").length, 1);
  h.element("cancel-batch").click();
  await h.settle();
  assert.match(list.textContent, /Enviado y verificado.*dos.txt1 kB · Sin enviar/);
  h.snapshot.download_batch = null;
  await h.tick();
  assert.match(list.textContent, /Enviado y verificado.*Sin enviar/);
  assert.equal(h.element("send-file").disabled, false);
});

for (const capable of [false, true])
  test(`single file keeps v1 flow for supports_inbox=${capable}`, async () => {
    const h = await ui(undefined, { peers: peers.map((peer) => ({ ...peer, supports_inbox: capable })) });
    h.onInvoke(async (command) => {
      if (command === "choose_sources") return sources.slice(0, 1);
      if (command === "offer_download") {
        const offered = transfer({ direction: "download" });
        h.snapshot.transfers = [offered];
        return offered;
      }
    });
    h.element("send-file").click();
    await h.settle();
    assert.equal(commands(h, "offer_download").length, 1);
    assert.equal(commands(h, "offer_download_batch").length, 0);
  });

test("outbound reservation arriving during source selection prevents a second offer", async () => {
  const h = await ui(undefined, { peers: inboxPeers });
  const gate = deferred();
  h.onInvoke(async (command) => {
    if (command === "choose_sources") return gate.promise;
  });
  h.element("send-file").click();
  await h.settle();
  h.snapshot.download_batch = downloadBatch();
  await h.tick();
  gate.resolve(sources);
  await h.settle();
  assert.equal(commands(h, "offer_download_batch").length, 0);
  assert.equal(commands(h, "offer_download").length, 0);
});

test("a disappearing peer is named, asks for a new QR and keeps the other peer", async () => {
  const { h } = await sendingBatch();
  h.snapshot.peers = [peers[1]];
  h.snapshot.transfers[0].state = "cancelled";
  await h.tick();
  assert.match(h.element("notice").textContent, /J4\+.*nuevo QR/);
  assert.equal(h.element("device-choices").querySelectorAll("button").length, 1);
  assert.match(h.element("device-choices").textContent, /Redmi Note 14/);
  assert.equal(h.element("send-file").disabled, false);
  assert.match(h.element("batch-status").textContent, /J4\+/);
});

test("simultaneous disappearance names every mobile without treating initial peers as lost", async () => {
  const h = await ui(undefined, { peers: inboxPeers });
  assert.equal(h.element("notice").textContent, "");
  h.snapshot.peers = [];
  await h.tick();
  assert.match(h.element("notice").textContent, /J4\+, Redmi Note 14.*nuevo QR/);
});

test("closing an active batch clears session UI and keeps the intentional closure notice", async () => {
  const h = await sendingInboxBatch();
  h.onInvoke(async (command) => {
    if (command === "choose_destination") return "C:\\Recibidos";
    if (command === "stop_session") h.snapshot.peers = [];
  });
  h.element("choose-folder").click();
  await h.settle();
  await h.open();
  assert.notEqual(h.element("descriptor").value, "");
  h.element("stop-session").click();
  await h.settle();
  assert.match(h.element("notice").textContent, /^Sesión cerrada\./);
  assert.equal(h.element("notice").textContent.includes("desconectado"), false);
  assert.equal(h.element("batch-panel").classList.contains("hidden"), true);
  assert.equal(h.element("descriptor").value, "");
  assert.equal(h.element("folder-label").textContent, "Se elige al aceptar cada archivo");
  assert.equal(h.element("send-file").disabled, true);
  h.snapshot.session = { session_id: "session-b", host: "192.168.1.20", port: 51337 };
  h.snapshot.download_batch = null;
  await h.tick();
  assert.equal(h.element("batch-panel").classList.contains("hidden"), true);
  assert.match(h.element("notice").textContent, /^Sesión cerrada\./);
});

test("session replacement never reports old peers as disconnected and resets folder and QR", async () => {
  const h = await ui(undefined, { peers: inboxPeers });
  h.onInvoke(async (command) => {
    if (command === "choose_destination") return "C:\\Recibidos";
  });
  h.element("choose-folder").click();
  await h.settle();
  await h.open();
  h.snapshot.session.session_id = "session-b";
  h.snapshot.peers = [];
  await h.tick();
  assert.equal(h.element("notice").textContent.includes("desconectado"), false);
  assert.equal(h.element("descriptor").value, "");
  assert.equal(h.element("folder-label").textContent, "Se elige al aceptar cada archivo");
});

test("peer departure preserves verified outbound rows and terminal snapshot results", async () => {
  const h = await sendingInboxBatch();
  h.snapshot.download_batch.files[0] = {
    ...h.snapshot.download_batch.files[0], transfer_id: "verified-a", state: "completed",
  };
  h.snapshot.download_batch.state = "cancelled";
  h.snapshot.peers = [inboxPeers[1]];
  await h.tick();
  assert.match(h.element("notice").textContent, /J4\+.*nuevo QR/);
  assert.match(h.element("batch-status").textContent, /Lote cancelado.*J4\+/);
  assert.match(h.element("batch-files").textContent, /Enviado y verificado.*Sin enviar/);
  assert.equal(h.element("cancel-batch").hidden, true);
  assert.equal(h.element("send-file").disabled, false);
});

test("a snapshot requested before intentional stop cannot restore its active batch", async () => {
  const h = await sendingInboxBatch();
  const gate = deferred();
  h.onSnapshot(async (value) => {
    await gate.promise;
    return value;
  });
  await h.tick();
  h.element("stop-session").click();
  await h.settle();
  gate.resolve();
  await h.settle();
  h.onSnapshot(null);
  await h.tick();
  assert.match(h.element("notice").textContent, /^Sesión cerrada\./);
  assert.equal(h.element("batch-panel").classList.contains("hidden"), true);
  assert.equal(h.element("send-file").disabled, true);
});

test("retry after QR re-pair selects a fresh peer and skips verified and unavailable sources", async () => {
  const retrySources = [...sources, {
    source: "C:\\tres.txt", file_name: "tres.txt", size_bytes: "1024",
  }].map((source) => ({
    ...source, modified: "1000000000",
  }));
  const h = await ui(undefined, { peers: inboxPeers });
  h.onInvoke(async (command, args) => {
    if (command === "choose_sources") return retrySources;
    if (command === "offer_download_batch") {
      h.snapshot.download_batch = downloadBatch({
        files: retrySources.map(({ file_name, size_bytes }) => ({
          file_name, size_bytes, transfer_id: null, state: "pending",
        })),
      });
      return { batch_id: "download-a", state: "awaiting_acceptance" };
    }
    if (command === "available_retry_sources") {
      assert.deepEqual(Array.from(args.sources, (item) => item.file_name), ["dos.txt", "tres.txt"]);
      return retrySources.slice(1, 2);
    }
    if (command === "offer_download") {
      const offered = transfer({ direction: "download", transfer_id: "fresh-offer" });
      h.snapshot.transfers.push(offered);
      return offered;
    }
  });
  h.element("send-file").click();
  await h.settle();
  const manifest = h.snapshot.download_batch;
  manifest.state = "cancelled";
  Object.assign(manifest.files[0], { transfer_id: "verified", state: "completed" });
  Object.assign(manifest.files[1], { transfer_id: "interrupted", state: "cancelled" });
  manifest.files[2].state = "not_sent";
  h.snapshot.transfers = [transfer({
    transfer_id: "interrupted", state: "cancelled", direction: "download",
    delivery_uncertain: false, error_code: "revoked",
  })];
  h.snapshot.peers = [inboxPeers[1]];
  await h.tick();
  assert.equal(h.element("retry-summary").classList.contains("hidden"), false);
  h.element("retry-send").click();
  assert.equal(h.element("retry-confirm").disabled, true);
  assert.equal(h.element("retry-pair").hidden, false);
  h.element("retry-pair").click();
  await h.settle();
  assert.equal(commands(h, "open_pairing_window").length, 1);
  h.snapshot.peers.push({ ...inboxPeers[0], id: "phone-fresh" });
  await h.tick();
  h.element("retry-send").click();
  assert.equal(h.element("retry-confirm").disabled, true);
  h.element("retry-devices").children[1].click();
  h.element("retry-confirm").click();
  await h.settle();
  await h.tick();
  assert.equal(commands(h, "offer_download_batch").length, 1);
  const offered = commands(h, "offer_download")[0].args;
  assert.equal(offered.peerId, "phone-fresh");
  assert.equal(offered.source, retrySources[1].source);
  assert.match(h.element("notice").textContent, /Se omitió un archivo/);
  assert.equal(commands(h, "accept_upload_batch").length, 0);
  assert.equal(commands(h, "accept_upload").length, 0);
});

test("ambiguous legacy retry asks the sender to check destination before offering again", async () => {
  const h = await ui(undefined, { peers });
  h.onInvoke(async (command, args) => {
    if (command === "choose_sources") return sources.slice(0, 1);
    if (command === "available_retry_sources") return args.sources;
    if (command === "offer_download") {
      const offered = transfer({ direction: "download" });
      h.snapshot.transfers = [offered];
      return offered;
    }
  });
  h.element("send-file").click();
  await h.settle();
  Object.assign(h.snapshot.transfers[0], {
    state: "failed", delivery_uncertain: true, error_code: "timeout",
  });
  await h.tick();
  h.element("retry-send").click();
  h.element("retry-devices").children[0].click();
  assert.match(h.element("retry-uncertain-files").textContent, /uno.txt/);
  assert.equal(h.element("retry-confirm").disabled, true);
  h.element("retry-include").checked = true;
  h.element("retry-include").onchange();
  h.element("retry-confirm").click();
  await h.settle();
  assert.equal(commands(h, "offer_download").length, 2);
});

test("incoming interruption enables receiving and exposes QR without accepting a request", async () => {
  const h = await ui(undefined, {
    transfers: [transfer({ state: "cancelled", direction: "upload", error_code: "revoked" })],
  });
  h.onInvoke(async (command) => {
    if (command === "set_receive_enabled") h.snapshot.receive_enabled = true;
  });
  h.element("current-transfer").querySelector('[data-transfer-action="prepare-receive"]').click();
  await h.settle();
  assert.equal(h.snapshot.receive_enabled, true);
  assert.equal(commands(h, "open_pairing_window").length, 1);
  assert.equal(commands(h, "accept_upload").length, 0);
  assert.equal(commands(h, "choose_destination").length, 0);
});

test("stream progress and throughput use decimal SI prefixes", async () => {
  const h = await ui(undefined, {
    transfers: [transfer({ size_bytes: "1000000000", state: "streaming" })],
  });
  h.snapshot.transfers[0].bytes_transferred = "1500000";
  await h.tick(1000);
  assert.match(h.element("current-transfer").textContent, /1,5 MB de 1 GB · 1,5 MB\/s/);
});


test("an interrupted inbound manifest can prepare reception before any file was offered", async () => {
  const h = await ui(undefined, {
    upload_batch: uploadBatch({ state: "cancelled" }),
  });
  h.onInvoke(async (command) => {
    if (command === "set_receive_enabled") h.snapshot.receive_enabled = true;
  });
  assert.equal(h.element("retry-receive-batch").hidden, false);
  h.element("retry-receive-batch").click();
  await h.settle();
  assert.equal(h.snapshot.receive_enabled, true);
  assert.equal(commands(h, "open_pairing_window").length, 1);
  assert.equal(commands(h, "accept_upload_batch").length, 0);
});

const queueSources = ["sin-extension", "foto.webp"].map((file_name) => ({
  source: `C:\\Users\\Personal\\${file_name}`, file_name, size_bytes: "5000000001", modified: "1234567890",
}));
const queueRows = (h) => h.element("waiting-files").children;
const queueTargets = (h) => queueRows(h)[0].querySelector(".waiting-targets").querySelectorAll("button");
const queueSend = (h) => queueRows(h)[0].querySelector('[data-queue-action="send"]');
function queueHandler(h, extra = () => {}) {
  h.onInvoke(async (command, args) => {
    if (command === "choose_sources") return queueSources;
    if (command === "inspect_selection") return { sources: queueSources, directories: [] };
    if (command === "available_retry_sources") return args.sources;
    if (command === "offer_download_batch") {
      const manifest = { batch_id: "queue-batch", state: "awaiting_acceptance", peer_id: args.peerId,
        files: args.sources.map((source) => ({ file_name: source.split("\\").pop(), size_bytes: "5000000001", state: "pending", transfer_id: null })) };
      h.snapshot.download_batch = manifest;
      return manifest;
    }
    if (command === "offer_download") {
      const offered = transfer({ direction: "download", peer_id: args.peerId, file_name: args.source });
      h.snapshot.transfers = [offered];
      return offered;
    }
    return extra(command, args);
  });
}

test("files can wait offline, survive reload and require an explicit target among several phones", async () => {
  const h = await ui(undefined, { session: null });
  queueHandler(h);
  assert.equal(h.element("send-file").disabled, false);
  assert.equal(h.element("queue-files").disabled, false);
  h.element("send-file").click();
  await h.settle();
  assert.match(h.element("waiting-count").textContent, /2 archivos/);
  assert.equal(commands(h, "offer_download").length, 0);
  const restored = await ui(undefined, { peers }, { storage: h.storage });
  queueHandler(restored);
  assert.match(restored.element("waiting-files").textContent, /sin-extension/);
  assert.equal(queueSend(restored).disabled, true);
  queueTargets(restored)[1].click();
  queueSend(restored).click();
  await restored.settle();
  const offer = commands(restored, "offer_download")[0];
  assert.equal(offer.args.peerId, "phone-b");
  assert.equal(offer.args.expected.modified, queueSources[0].modified);
  assert.equal(queueRows(restored).length, 1); // Empty-state paragraph; selection transferred to retry ownership.
  assert.match(restored.element("waiting-count").textContent, /0 archivos/);
  await restored.tick();
  assert.equal(commands(restored, "offer_download").length, 1);
});

test("Dejar en espera is available while another transfer owns the slot and permits removing individual files", async () => {
  const h = await ui(undefined, { peers, transfers: [transfer({ state: "streaming" })] });
  queueHandler(h);
  assert.equal(h.element("queue-files").disabled, false);
  h.element("queue-files").click();
  await h.settle();
  queueTargets(h)[0].click();
  assert.equal(queueSend(h).disabled, true);
  queueRows(h)[0].querySelector(".text-button").click();
  assert.match(h.element("waiting-count").textContent, /1 archivos/);
  assert.doesNotMatch(h.element("waiting-files").textContent, /sin-extension/);
  queueRows(h)[0].querySelector('[data-queue-action="remove"]').click();
  assert.match(h.element("waiting-count").textContent, /0 archivos/);
  assert.equal(commands(h, "offer_download").length, 0);
});

test("general native drop uses real paths, converts physical coordinates and never guesses a target", async () => {
  const points = [];
  const h = await ui(undefined, { peers }, { ratio: 2, onPoint: (x, y) => points.push([x, y]) });
  queueHandler(h);
  const paths = queueSources.map((source) => source.source);
  await h.drop({ type: "enter", paths, position: { x: 240, y: 100 } });
  assert.equal(h.element("waiting-panel").classList.contains("drop-target"), true);
  await h.drop({ type: "drop", paths, position: { x: 240, y: 100 } });
  assert.deepEqual(points, [[120, 50], [120, 50]]);
  assert.deepEqual(Array.from(commands(h, "inspect_selection")[0].args.paths), paths);
  assert.equal(queueSend(h).disabled, true);
  assert.equal(commands(h, "offer_download_batch").length, 0);
  h.pagehide();
  assert.equal(h.dropUnlistened(), true);
});

test("native drop over an authenticated free phone offers only to that phone through receiver consent", async () => {
  const h = await ui(undefined, { peers: peers.map((peer) => ({ ...peer, supports_inbox: true })) });
  queueHandler(h);
  const phone = h.element("device-choices").querySelectorAll("button")[1];
  await h.drop({ type: "drop", paths: queueSources.map((source) => source.source), position: { x: 10, y: 10 } }, phone);
  const offer = commands(h, "offer_download_batch")[0];
  assert.equal(offer.args.peerId, "phone-b");
  assert.equal(offer.args.sessionId, "session-a");
  assert.equal(offer.args.expected[0].modified, queueSources[0].modified);
  assert.equal(commands(h, "accept_upload").length, 0);
  assert.equal(commands(h, "offer_download").length, 0);
  assert.match(h.element("waiting-count").textContent, /0 archivos/);
  await h.tick();
  assert.equal(commands(h, "offer_download_batch").length, 1);
});

test("native drop over a busy phone queues for that exact destination and offers once after it becomes free", async () => {
  const h = await ui(undefined, { peers, transfers: [transfer({ state: "streaming" })] });
  queueHandler(h);
  await h.drop({ type: "drop", paths: [queueSources[0].source], position: { x: 10, y: 10 } },
    h.element("device-choices").querySelectorAll("button")[1]);
  assert.match(h.element("waiting-files").textContent, /Destino: Redmi/);
  h.snapshot.transfers = [];
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 1);
  assert.equal(commands(h, "offer_download")[0].args.peerId, "phone-b");
  await h.tick();
  assert.equal(commands(h, "offer_download").length, 1);
});

test("changed or missing queued sources keep the selection and target without creating any offer", async () => {
  const h = await ui(undefined, { peers });
  h.onInvoke(async (command) => {
    if (command === "choose_sources") return queueSources;
    if (command === "available_retry_sources") return queueSources.slice(1);
  });
  h.element("queue-files").click();
  await h.settle();
  queueTargets(h)[1].click();
  queueSend(h).click();
  await h.settle();
  assert.match(h.element("waiting-count").textContent, /2 archivos/);
  assert.match(h.element("waiting-files").textContent, /Destino: Redmi/);
  assert.match(h.element("notice").textContent, /cambió o ya no está disponible/);
  assert.equal(commands(h, "offer_download").length, 0);
});

test("a rejected manifest retains queued paths and target; polling never retries automatically", async () => {
  const h = await ui(undefined, { peers: peers.map((peer) => ({ ...peer, supports_inbox: true })) });
  h.onInvoke(async (command, args) => {
    if (command === "choose_sources") return queueSources;
    if (command === "available_retry_sources") return args.sources;
    if (command === "offer_download_batch") throw "busy";
  });
  h.element("queue-files").click(); await h.settle();
  queueTargets(h)[1].click(); queueSend(h).click(); await h.settle();
  assert.match(h.element("waiting-count").textContent, /2 archivos/);
  assert.match(h.element("waiting-files").textContent, /Destino: Redmi/);
  await h.tick();
  assert.equal(commands(h, "offer_download_batch").length, 1);
  assert.equal(queueSend(h).disabled, false);
});

test("a restart during an in-flight offer requires checking the remote folder before a new explicit offer", async () => {
  const h = await ui(undefined, { peers: [{ ...peers[0], supports_inbox: true }] });
  let resolveOffer;
  h.onInvoke(async (command, args) => {
    if (command === "choose_sources") return queueSources;
    if (command === "available_retry_sources") return args.sources;
    if (command === "offer_download_batch") return new Promise((resolve) => { resolveOffer = resolve; });
  });
  h.element("queue-files").click(); await h.settle();
  queueTargets(h)[0].click(); queueSend(h).click(); await h.settle();
  const restored = await ui(undefined, { peers }, { storage: new Map(h.storage) });
  assert.match(restored.element("waiting-files").textContent, /Puede que el móvil ya la tenga/);
  queueTargets(restored)[1].click();
  assert.equal(queueSend(restored).disabled, true);
  assert.equal(commands(restored, "offer_download").length, 0);
  const review = queueRows(restored)[0].querySelectorAll("button").find((button) => /He comprobado/.test(button.textContent));
  review.click();
  assert.equal(queueSend(restored).disabled, false);
  resolveOffer({ batch_id: "authorised", state: "awaiting_acceptance" });
  await h.settle();
});

test("queued destinations are invalidated when the session closes and are not assigned to the next phone", async () => {
  const h = await ui(undefined, { peers });
  queueHandler(h);
  h.element("queue-files").click(); await h.settle();
  queueTargets(h)[1].click();
  h.element("stop-session").click(); await h.settle();
  assert.match(h.element("waiting-count").textContent, /2 archivos/);
  h.snapshot.session = { session_id: "another-session", host: "192.168.1.2", port: 12345 };
  h.snapshot.peers = [peers[0]];
  await h.tick();
  assert.equal(queueSend(h).disabled, true);
  assert.equal(queueTargets(h)[0].getAttribute("aria-pressed"), "false");
});

test("an ambiguous queued offer failure requires checking the destination and never retries itself", async () => {
  const h = await ui(undefined, { peers: [{ ...peers[0], supports_inbox: true }] });
  h.onInvoke(async (command, args) => {
    if (command === "choose_sources") return queueSources;
    if (command === "available_retry_sources") return args.sources;
    if (command === "offer_download_batch") throw "IPC connection interrupted";
  });
  h.element("queue-files").click(); await h.settle();
  queueTargets(h)[0].click(); queueSend(h).click(); await h.settle();
  assert.equal(queueSend(h).disabled, true);
  assert.match(h.element("waiting-files").textContent, /Puede que el móvil ya la tenga/);
  await h.tick();
  assert.equal(commands(h, "offer_download_batch").length, 1);
});

test("a storage failure before authorization keeps queued choices and does not offer", async () => {
  const h = await ui(undefined, { peers }, { storageFail: true });
  queueHandler(h);
  h.element("queue-files").click(); await h.settle();
  queueTargets(h)[0].click(); queueSend(h).click(); await h.settle();
  assert.match(h.element("waiting-count").textContent, /2 archivos/);
  assert.match(h.element("notice").textContent, /guardar el estado/);
  assert.equal(commands(h, "offer_download").length, 0);
});

const folderDirectories = ["Estudios", "Estudios/Apuntes", "Estudios/Vacia"].map((relative_path) => ({
  source: `C:\\Users\\Personal\\${relative_path.replaceAll("/", "\\")}`, relative_path,
}));
const folderSelection = {
  directories: folderDirectories,
  sources: ["primero", "segundo"].map((name) => ({
    source: `C:\\Users\\Personal\\Estudios\\Apuntes\\${name}`,
    file_name: name, size_bytes: "3", modified: "1234567890", relative_path: `Estudios/Apuntes/${name}`,
  })),
};
function folderHandler(h, selection = folderSelection) {
  h.onInvoke(async (command, args) => {
    if (command === "choose_source_folder" || command === "inspect_selection") return structuredClone(selection);
    if (command === "available_retry_sources") return args.sources;
    if (command === "offer_folder_selection") {
      const manifest = {
        batch_id: `folder-${commands(h, command).length}`, state: "awaiting_acceptance", peer_id: args.peerId,
        directories: args.selection.directories.map((directory) => directory.relative_path),
        files: args.selection.sources.map((source) => ({ ...source, transfer_id: null, state: "pending" })),
      };
      h.snapshot.download_batch = manifest;
      return manifest;
    }
  });
}

test("empty folders can be selected offline, survive reload and explain why legacy phones cannot receive them", async () => {
  const h = await ui(undefined, { session: null });
  folderHandler(h, { sources: [], directories: folderDirectories });
  h.element("send-folder").click(); await h.settle();
  assert.match(h.element("waiting-count").textContent, /3 carpetas/);
  assert.match(h.element("waiting-files").textContent, /Estudios\/Vacia/);
  const restored = await ui(undefined, { peers }, { storage: h.storage });
  assert.match(restored.element("waiting-files").textContent, /no admiten carpetas/);
  assert.equal(queueTargets(restored)[0].disabled, true);
  assert.equal(queueSend(restored).disabled, true);
  assert.equal(commands(restored, "offer_download").length, 0);
  assert.equal(commands(restored, "offer_download_batch").length, 0);
});

test("native folder drop keeps relative paths and empty directories in one approved manifest", async () => {
  const h = await ui(undefined, { peers: [{ ...peers[1], supports_inbox: true, supports_folders: true }] });
  folderHandler(h);
  await h.drop({ type: "drop", paths: ["C:\\Users\\Personal\\Estudios"], position: { x: 40, y: 40 } },
    h.element("device-choices").querySelector("button"));
  const offer = commands(h, "offer_folder_selection")[0];
  assert.equal(offer.args.peerId, "phone-b");
  assert.equal(offer.args.selection.sources[0].relative_path, "Estudios/Apuntes/primero");
  assert.equal(offer.args.selection.directories[2].relative_path, "Estudios/Vacia");
  assert.equal(commands(h, "offer_download").length, 0);
  assert.equal(commands(h, "offer_download_batch").length, 0);
  assert.match(h.element("batch-directories").textContent, /Estudios\/Vacia/);
  assert.match(h.element("batch-files").textContent, /Estudios\/Apuntes\/primero/);
});

test("a legacy-targeted folder drop stays queued and never degrades into loose files", async () => {
  const h = await ui(undefined, { peers });
  folderHandler(h);
  await h.drop({ type: "drop", paths: ["C:\\Users\\Personal\\Estudios"], position: { x: 40, y: 40 } },
    h.element("device-choices").querySelector("button"));
  assert.match(h.element("waiting-files").textContent, /no admite carpetas/);
  assert.equal(queueSend(h).disabled, true);
  assert.equal(commands(h, "offer_folder_selection").length, 0);
  assert.equal(commands(h, "offer_download").length, 0);
  assert.equal(commands(h, "offer_download_batch").length, 0);
});

test("an incoming folder-only manifest stays visible and requires explicit local approval", async () => {
  const incoming = { batch_id: "incoming-empty", peer_id: "phone-a", state: "awaiting_acceptance", files: [], directories: ["Vacia", "Vacia/Hija"] };
  const h = await ui(undefined, { peers, upload_batch: incoming });
  h.onInvoke(async (command) => {
    if (command === "choose_destination") return "C:\\Received";
    if (command === "accept_upload_batch") { h.snapshot.upload_batch.state = "completed"; return { state: "completed" }; }
  });
  assert.equal(h.element("batch-panel").classList.contains("hidden"), false);
  assert.equal(h.element("accept-batch").hidden, false);
  assert.match(h.element("batch-directories").textContent, /Vacia\/Hija/);
  assert.equal(commands(h, "accept_upload_batch").length, 0);
  h.element("accept-batch").click(); await h.settle();
  assert.equal(commands(h, "accept_upload_batch").length, 1);
  assert.match(h.element("batch-status").textContent, /Lote completado/);
});

test("folder retry keeps the tree, omits verified files and explains that a new root is created", async () => {
  const h = await ui(undefined, { peers: [{ ...peers[0], supports_inbox: true, supports_folders: true }] });
  folderHandler(h);
  h.element("send-folder").click(); await h.settle();
  queueTargets(h)[0].click(); queueSend(h).click(); await h.settle();
  const first = h.snapshot.download_batch;
  first.state = "failed";
  first.files[0].state = "completed";
  first.files[0].transfer_id = "verified-folder-file";
  first.files[1].state = "not_sent";
  await h.tick();
  h.element("retry-send").click(); await h.settle();
  assert.match(h.element("retry-hint").textContent, /raíz nueva/);
  h.element("retry-devices").querySelector("button").click();
  h.element("retry-confirm").click(); await h.settle();
  const retried = commands(h, "offer_folder_selection")[1];
  assert.equal(retried.args.selection.sources.length, 1);
  assert.equal(retried.args.selection.sources[0].relative_path, "Estudios/Apuntes/segundo");
  assert.equal(retried.args.selection.directories.length, 3);
  assert.equal(commands(h, "offer_download").length, 0);
});

test("folder-only retry after a lost session requires checking the remote folder", async () => {
  const h = await ui(undefined, { peers: [{ ...peers[0], supports_inbox: true, supports_folders: true }] });
  folderHandler(h, { sources: [], directories: folderDirectories });
  h.element("send-folder").click(); await h.settle();
  queueTargets(h)[0].click(); queueSend(h).click(); await h.settle();
  h.snapshot.session = null;
  h.snapshot.download_batch = null;
  await h.tick();
  h.snapshot.session = { session_id: "new", host: "192.168.1.8", port: 54321 };
  await h.tick();
  h.element("retry-send").click(); await h.settle();
  h.element("retry-devices").querySelector("button").click();
  assert.equal(h.element("retry-confirm").disabled, true);
  assert.equal(h.element("retry-uncertain").classList.contains("hidden"), false);
  h.element("retry-include").checked = true;
  h.element("retry-include").onchange();
  assert.equal(h.element("retry-confirm").disabled, false);
  h.element("retry-confirm").click(); await h.settle();
  assert.equal(commands(h, "offer_folder_selection").length, 2);
  assert.equal(commands(h, "offer_download").length, 0);
});
