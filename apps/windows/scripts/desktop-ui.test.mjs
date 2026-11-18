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
      h.snapshot.session = { host: args.bindIpv4, port: args.port };
    if (command === "choose_destination") return "C:\\Recepcion";
  });
  h.element("bind-ip").value = " 192.168.1.8 ";
  h.element("session-form").onsubmit({ preventDefault() {} });
  await h.settle();
  assert.equal(commands(h, "start_session")[0].args.bindIpv4, "192.168.1.8");
  assert.equal(commands(h, "start_session")[0].args.port, 51337);
  assert.equal(h.element("session-setup").classList.contains("hidden"), true);
  h.element("choose-folder").click();
  await h.settle();
  assert.equal(h.element("folder-label").textContent, "C:\\Recepcion");
  h.element("stop-session").click();
  await h.settle();
  assert.equal(commands(h, "stop_session").length, 1);
  assert.equal(h.element("send-file").disabled, true);
  assert.equal(
    h.element("folder-label").textContent,
    "Se elige al aceptar cada archivo",
  );
});

test("send picks a real file and offers it to the selected peer; cancelled picker sends nothing", async () => {
  const h = await ui(undefined, { peers });
  let source = "C:\\Documentos\\AY01.pdf";
  h.onInvoke(async (command) =>
    command === "choose_source" ? source : undefined,
  );
  h.element("device-choices").querySelectorAll("button")[1].click();
  h.element("send-file").click();
  await h.settle();
  assert.equal(commands(h, "offer_download")[0].args.peerId, "phone-b");
  assert.equal(commands(h, "offer_download")[0].args.source, source);
  source = null;
  h.element("send-file").click();
  await h.settle();
  assert.equal(commands(h, "choose_source").length, 2);
  assert.equal(commands(h, "offer_download").length, 1);
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
  assert.equal(h.element("home").getAttribute("aria-current"), "page");
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
