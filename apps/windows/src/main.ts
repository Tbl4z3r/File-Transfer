import { encodePairingQr } from "./pairing-qr.js";
type Peer = { id: string; name: string; supports_inbox: boolean };
type Transfer = {
  transfer_id: string;
  direction: "upload" | "download";
  file_name: string;
  size_bytes: string;
  bytes_transferred: string;
  state: string;
  error_code?: string;
  sha256?: string;
  peer_id: string;
  destination: string | null;
};
type BatchFile = {
  file_name: string;
  size_bytes: string;
  transfer_id: string | null;
  state: string;
};
type TransferBatch = {
  batch_id: string;
  peer_id: string;
  state: string;
  files: BatchFile[];
};
type Source = { source: string; file_name: string; size_bytes: string };
type BatchStatus = { batch_id: string; state: string };
type Snapshot = {
  session: null | {
    session_id: string;
    host: string;
    port: number;
    cert_sha256: string;
  };
  receive_enabled: boolean;
  peers: Peer[];
  transfers: Transfer[];
  upload_batch: TransferBatch | null;
  download_batch: TransferBatch | null;
};
type Descriptor = {
  cert_sha256: string;
  pairing_token: string;
  [key: string]: unknown;
};
declare global {
  interface Window {
    __TAURI__?: {
      core: {
        invoke<T>(command: string, args?: Record<string, unknown>): Promise<T>;
      };
    };
  }
}
const $ = <T extends HTMLElement>(id: string): T =>
  document.getElementById(id) as T;
const invoke = <T>(
  command: string,
  args?: Record<string, unknown>,
): Promise<T> => {
  if (!window.__TAURI__) return Promise.reject("desktop_required");
  return window.__TAURI__.core.invoke<T>(command, args);
};
const messages: Record<string, string> = {
  invalid_request:
    "Comprueba que la IPv4 pertenezca a este PC y esté conectada a tu red.",
  busy: "Termina o cancela la transferencia actual antes de iniciar otra.",
  io_error:
    "No se pudo acceder al archivo o abrir la conexión. Comprueba la carpeta, los permisos y que la IPv4 pertenezca a este PC.",
  insufficient_space:
    "No hay espacio suficiente en el destino. Elige otra carpeta o libera espacio.",
  source_changed:
    "El archivo de origen ha cambiado. Vuelve a seleccionarlo y enviarlo.",
  invalid_name:
    "El nombre contiene caracteres que Windows no admite. Cambia su nombre antes de enviarlo.",
  invalid_state: "La transferencia ya cambió de estado. Revisa su resultado.",
  session_closed: "Inicia una sesión para conectar el móvil.",
  revoked: "El dispositivo fue revocado. Vuelve a emparejarlo para conectar.",
  timeout:
    "La conexión no avanzó a tiempo. Comprueba la red y vuelve a enviar el archivo.",
  network_error:
    "La conexión se interrumpió. Vuelve a enviar el archivo desde el principio.",
  size_mismatch: "El archivo llegó incompleto. Vuelve a enviarlo.",
  integrity_mismatch:
    "El archivo no superó la verificación. Vuelve a enviarlo.",
  desktop_required:
    "Abre esta pantalla desde la aplicación de escritorio File Transfer.",
  storage_in_use:
    "File Transfer ya está usando sus datos. Cierra la otra instancia.",
};
let selectedPeer = "";
let selectedDestination: string | null = null;
let latest: Snapshot | null = null;
let peerSignature = "";
let descriptorUntil = 0;
let pairingGeneration = 0;
let refreshing = false;
let busy = false;
type SendBatch = {
  sessionId: string;
  peerId: string;
  sources: (Source & BatchFile)[];
  state: string;
  index: number;
  transferId: string | null;
};
let batch: SendBatch | null = null;
let shownSendBatch: SendBatch | null = null;
let shownUploadBatch: TransferBatch | null = null;
let shownDownloadBatch: TransferBatch | null = null;
const peerNames = new Map<string, string>();
let listSessionId: string | null = null;
let batchListKey = "";
let batchRows: { row: HTMLElement; name: HTMLElement; detail: HTMLElement }[] =
  [];
let offering = false;
let offerRevision = 0;
function discardBatch(message?: string): void {
  if (batch) {
    batch.state = batch.sources.every((f) => f.state === "completed")
      ? "completed"
      : batch.sources.some((f) => f.state === "failed")
        ? "failed"
        : "cancelled";
    for (const file of batch.sources)
      if (file.state === "pending") file.state = "unsent";
  }
  batch = null;
  if (message) notice(message);
  updateControls();
}
async function cancelBatch(): Promise<void> {
  const current = batch;
  const id = current?.transferId;
  if (current && id) current.sources[current.index].state = "cancelled";
  discardBatch("Lote cancelado. Los archivos pendientes no se enviarán.");
  // An in-flight offer is cancelled by sendNext when its IPC reply arrives.
  if (id) {
    try {
      await invoke("cancel_transfer", { transferId: id });
    } catch (error) {
      notice(
        messages[String(error)] ??
          "No se pudo cancelar el archivo. Revisa su estado.",
        true,
      );
    }
  }
  await refresh();
}
async function sendNext(current: SendBatch): Promise<void> {
  if (shownUploadBatch && terminal(shownUploadBatch.state))
    shownUploadBatch = null;
  offering = true;
  updateControls();
  try {
    const transfer = await invoke<Transfer>("offer_download", {
      peerId: current.peerId,
      source: current.sources[current.index].source,
      sessionId: current.sessionId,
    });
    Object.assign(current.sources[current.index], {
      file_name: transfer.file_name,
      size_bytes: transfer.size_bytes,
      transfer_id: transfer.transfer_id,
      state: transfer.state,
    });
    if (batch !== current) {
      // Cancellation/revocation/close may happen while IPC is creating this offer.
      try {
        const result = await invoke<Transfer>("cancel_transfer", {
          transferId: transfer.transfer_id,
        });
        if (result) current.sources[current.index].state = result.state;
      } catch {
        /* Already terminal or session closed. */
      }
      return;
    }
    offerRevision++;
    current.transferId = transfer.transfer_id;
    notice("Oferta enviada. Confirma cada archivo en el móvil.");
  } catch (error) {
    if (batch === current) {
      current.sources[current.index].state = "failed";
      discardBatch();
      notice(
        `${messages[String(error)] ?? "No se pudo ofrecer el archivo."} Los archivos pendientes no se enviarán.`,
        true,
      );
    }
  } finally {
    offering = false;
    updateControls();
    void refresh().catch(() => {});
  }
}
function advanceBatch(snapshot: Snapshot): void {
  const current = batch;
  if (!current) return;
  if (
    snapshot.session?.session_id !== current.sessionId ||
    !snapshot.peers.some((peer) => peer.id === current.peerId)
  ) {
    discardBatch(
      "El lote se ha detenido porque la sesión o el dispositivo ya no están disponibles.",
    );
    return;
  }
  if (current.transferId) {
    const transfer = snapshot.transfers.find(
      (t) => t.transfer_id === current.transferId,
    );
    if (transfer)
      Object.assign(current.sources[current.index], {
        file_name: transfer.file_name,
        size_bytes: transfer.size_bytes,
        state: transfer.state,
      });
    if (
      !transfer ||
      transfer.state === "failed" ||
      transfer.state === "cancelled"
    ) {
      discardBatch(
        "El lote se ha detenido. Los archivos pendientes no se enviarán.",
      );
      return;
    }
    if (transfer.state !== "completed") return;
    current.index++;
    current.transferId = null;
    if (current.index === current.sources.length) {
      discardBatch(
        `Lote completado: ${current.sources.length} archivos enviados y verificados.`,
      );
      return;
    }
  }
  // Respect every other v1 transfer, including an incoming request during a gap.
  if (
    !offering &&
    !busy &&
    !incomingReserved() &&
    !outgoingReserved() &&
    !snapshot.transfers.some((t) => !terminal(t.state))
  )
    void sendNext(current);
}
const samples = new Map<
  string,
  { bytes: bigint; time: number; speed: number }
>();
const speeds = new Map<string, number>();
const panels = [
  "activity-panel",
  "devices-panel",
  "settings-panel",
  "pair-panel",
];
const rails: Record<string, string> = {
  "activity-panel": "open-activity",
  "devices-panel": "open-devices",
  "settings-panel": "open-settings",
};
function icon(name: string): SVGSVGElement {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.classList.add("icon");
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", `#icon-${name}`);
  svg.append(use);
  return svg;
}
function node<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className = "",
  text = "",
): HTMLElementTagNameMap[K] {
  const element = document.createElement(tag);
  element.className = className;
  element.textContent = text;
  return element;
}
function notice(message: string, error = false): void {
  const element = $("notice");
  element.textContent = message;
  element.classList.remove("hidden");
  element.classList.toggle("error", error);
  for (const id of panels) {
    const panel = $<HTMLDialogElement>(id);
    const local = panel.querySelector<HTMLElement>(".dialog-notice");
    if (panel.open && local) {
      local.textContent = message;
      local.classList.remove("hidden");
      local.classList.toggle("failed", error);
    }
  }
}
function markRail(id?: string): void {
  for (const rail of Object.values(rails)) {
    if (rail === id) $(rail).setAttribute("aria-current", "page");
    else $(rail).removeAttribute("aria-current");
  }
}
function showPanel(id: string): void {
  for (const other of panels) {
    if (other !== id && $<HTMLDialogElement>(other).open) {
      if (other === "pair-panel") hideDescriptor();
      else $<HTMLDialogElement>(other).close();
    }
  }
  const panel = $<HTMLDialogElement>(id);
  if (!panel.open) {
    panel.querySelector(".dialog-notice")?.classList.add("hidden");
    panel.showModal();
  }
  markRail(rails[id]);
}
async function action(run: () => Promise<void>): Promise<void> {
  if (busy) return;
  busy = true;
  updateControls();
  try {
    await run();
    await refresh();
  } catch (error) {
    notice(
      messages[String(error)] ??
        "No se pudo completar la operación. Comprueba la conexión y vuelve a intentarlo.",
      true,
    );
  } finally {
    busy = false;
    updateControls();
    void refresh().catch(() => {});
  }
}
function formatBytes(value: bigint): string {
  const n = Number(value);
  if (n < 1024) return `${n} B`;
  const unit = Math.min(Math.floor(Math.log(n) / Math.log(1024)), 4);
  return `${(n / 1024 ** unit).toLocaleString("es", { maximumFractionDigits: 1 })} ${["B", "KiB", "MiB", "GiB", "TiB"][unit]}`;
}
function terminal(state: string): boolean {
  return ["completed", "failed", "cancelled"].includes(state);
}
function hideDescriptor(): void {
  pairingGeneration++;
  descriptorUntil = 0;
  $<HTMLImageElement>("pair-qr").removeAttribute("src");
  $("pair-qr").classList.add("hidden");
  $("qr-error").classList.add("hidden");
  $<HTMLTextAreaElement>("descriptor").value = "";
  $("fingerprint").textContent = "";
  $<HTMLDetailsElement>("manual-pair").open = false;
  const panel = $<HTMLDialogElement>("pair-panel");
  if (panel.open) panel.close();
}
function peerName(id: string): string {
  return (
    latest?.peers.find((peer) => peer.id === id)?.name ??
    peerNames.get(id) ?? "Dispositivo"
  );
}
function stateLabel(t: Transfer): string {
  const labels: Record<string, string> = {
    awaiting_acceptance:
      t.direction === "upload"
        ? "Solicitud entrante"
        : "Esperando aceptación en el móvil",
    ready: "Preparado",
    streaming: t.direction === "upload" ? "Recibiendo" : "Enviando",
    awaiting_verification: "Verificando integridad",
    completed:
      t.direction === "upload"
        ? "Recibido y verificado"
        : "Enviado y verificado",
    failed: "Interrumpido",
    cancelled:
      t.error_code === "revoked"
        ? "Dispositivo revocado"
        : t.error_code === "session_closed"
          ? "Sesión cerrada"
          : "Cancelado",
  };
  return labels[t.state] ?? "Estado no disponible";
}
function updateControls(): void {
  const active = Boolean(latest?.session);
  const hasPending = latest?.transfers.some((t) => !terminal(t.state)) ?? false;
  $("home-content").setAttribute("aria-busy", String(busy));
  $<HTMLButtonElement>("start-session").disabled = busy || active;
  $<HTMLButtonElement>("stop-session").disabled = busy || !active;
  $<HTMLInputElement>("bind-ip").disabled = active || busy;
  $<HTMLButtonElement>("pair").disabled = !active || busy;
  $<HTMLButtonElement>("receive").disabled = !active || busy;
  $<HTMLButtonElement>("choose-folder").disabled = !active || busy;
  $<HTMLButtonElement>("send-file").disabled =
    !active ||
    !selectedPeer ||
    hasPending ||
    incomingReserved() ||
    outgoingReserved() ||
    busy ||
    Boolean(batch) ||
    offering;
  renderBatch();
  $("receive").setAttribute(
    "aria-pressed",
    String(latest?.receive_enabled ?? false),
  );
  $("receive-label").textContent = latest?.receive_enabled
    ? "Dejar de recibir"
    : "Recibir";
  $("receive-setting").textContent = latest?.receive_enabled
    ? "Disponible para recibir solicitudes. Autoriza cada archivo o lote."
    : "La recepción está desactivada.";
  $("action-hint").textContent = !active
    ? "Inicia una sesión para conectar el móvil."
    : !latest?.peers.length
      ? "Muestra el QR para emparejar un móvil."
      : outgoingReserved()
        ? latest?.download_batch?.state === "awaiting_acceptance"
          ? "Esperando aceptación en el móvil. Puedes cancelar el lote completo."
          : "Enviando el lote autorizado. Puedes cancelar los archivos pendientes."
        : batch
          ? "Cada archivo necesita confirmación en el móvil. Puedes cancelar el lote completo."
          : incomingReserved()
            ? latest?.upload_batch?.state === "awaiting_acceptance"
              ? "Revisa los archivos del lote y acéptalo o recházalo aquí."
              : "Recibiendo el lote autorizado. Puedes cancelar los archivos pendientes."
            : hasPending
              ? "Termina o cancela la transferencia actual para enviar otra."
              : latest.receive_enabled
                ? "Disponible para recibir. Autoriza cada archivo o lote aquí."
                : "Elige un dispositivo para enviar o activa Recibir.";
  $("folder-label").textContent =
    selectedDestination ?? "Se elige al aceptar cada archivo";
  $("folder-hint").textContent = selectedDestination
    ? "Para las siguientes aceptaciones de esta sesión."
    : "Puedes elegir una carpeta para esta sesión.";
  $("choose-folder").setAttribute(
    "aria-label",
    selectedDestination
      ? `Cambiar carpeta de recepción: ${selectedDestination}`
      : "Elegir carpeta de recepción",
  );
  $("choose-folder").setAttribute(
    "title",
    selectedDestination ?? "Elegir carpeta de recepción",
  );
  for (const button of document.querySelectorAll<HTMLButtonElement>(
    "[data-transfer-action], [data-revoke]",
  ))
    button.disabled = busy;
}
function incomingReserved(): boolean {
  return Boolean(latest?.upload_batch && !terminal(latest.upload_batch.state));
}
function outgoingReserved(): boolean {
  return Boolean(latest?.download_batch && !terminal(latest.download_batch.state));
}
function batchFileLabel(
  file: BatchFile,
  incoming: boolean,
  state: string,
): string {
  if (!file.transfer_id) {
    if (file.state === "failed") return "No se pudo enviar";
    if (terminal(state) || file.state === "unsent" || file.state === "not_sent")
      return "Sin enviar";
    return state === "awaiting_acceptance"
      ? "Pendiente de aceptación"
      : "Pendiente";
  }
  const labels: Record<string, string> = {
    awaiting_acceptance: "Esperando aceptación en el móvil",
    ready: "Preparado",
    streaming: incoming ? "Recibiendo" : "Enviando",
    awaiting_verification: "Verificando integridad",
    completed: incoming ? "Recibido y verificado" : "Enviado y verificado",
    failed: "Interrumpido",
    cancelled: "Cancelado",
  };
  return labels[file.state] ?? "Pendiente";
}
function renderBatch(): void {
  const incoming = shownUploadBatch;
  const download = shownDownloadBatch;
  const outgoing = shownSendBatch;
  const files = incoming?.files ?? download?.files ?? outgoing?.sources ?? [];
  const state = incoming?.state ?? download?.state ?? outgoing?.state ?? "";
  const key = incoming
    ? `in/${incoming.batch_id}`
    : download
      ? `download/${download.batch_id}`
      : outgoing
        ? `out/${outgoing.sessionId}/${outgoing.sources[0]?.source}`
        : "";
  const waiting = Boolean(incoming && state === "awaiting_acceptance");
  $("batch-panel").classList.toggle("hidden", !files.length);
  const accept = $<HTMLButtonElement>("accept-batch");
  const cancel = $<HTMLButtonElement>("cancel-batch");
  accept.hidden = !waiting;
  accept.disabled = busy || !waiting || !incomingReserved();
  cancel.hidden = !files.length || terminal(state);
  cancel.disabled = busy || terminal(state) || (!incoming && !download && !batch);
  cancel.textContent = waiting ? "Rechazar lote" : "Cancelar lote";
  const completed = files.filter((f) => f.state === "completed").length;
  const label =
    state === "awaiting_acceptance"
      ? incoming
        ? "Solicitud entrante"
        : "Esperando aceptación en el móvil"
      : state === "completed"
        ? "Lote completado"
        : state === "failed"
          ? "Lote interrumpido"
          : state === "cancelled"
            ? "Lote cancelado"
            : "Lote en curso";
  $("batch-status").textContent = incoming
    ? `${label} · ${files.length} archivos · ${completed} verificados · ${peerName(incoming.peer_id)}`
    : download
      ? `${label} · ${files.length} archivos · ${completed} verificados · ${peerName(download.peer_id)}`
      : outgoing
        ? terminal(state)
          ? `${label} · ${completed} de ${files.length} verificados · ${peerName(outgoing.peerId)}`
          : `Archivo ${outgoing.index + 1} de ${files.length} · ${files.length - outgoing.index - 1} pendientes · ${peerName(outgoing.peerId)}`
        : "";
  const list = $("batch-files");
  if (batchListKey !== key || batchRows.length !== files.length) {
    batchListKey = key;
    batchRows = files.map(() => {
      const row = node("li", "batch-file");
      const name = node("span", "batch-file-name");
      const detail = node("span", "batch-file-detail");
      row.append(name, detail);
      return { row, name, detail };
    });
    list.replaceChildren(...batchRows.map((f) => f.row));
    list.scrollTop = 0;
  }
  files.forEach((file, i) => {
    batchRows[i].name.textContent = file.file_name;
    batchRows[i].detail.textContent =
      `${formatBytes(BigInt(file.size_bytes))} · ${batchFileLabel(file, Boolean(incoming), state)}`;
  });
}
async function acceptIncomingBatch(): Promise<void> {
  const pending = latest?.upload_batch;
  const sessionId = latest?.session?.session_id;
  if (!pending || pending.state !== "awaiting_acceptance" || !sessionId) return;
  await action(async () => {
    const destination =
      selectedDestination ??
      (await invoke<string | null>("choose_destination"));
    if (
      !destination ||
      latest?.session?.session_id !== sessionId ||
      latest.upload_batch?.batch_id !== pending.batch_id ||
      latest.upload_batch.state !== "awaiting_acceptance"
    )
      return;
    await invoke<BatchStatus>("accept_upload_batch", {
      batchId: pending.batch_id,
      destination,
    });
    offerRevision++;
    selectedDestination = destination;
  });
}
async function cancelIncomingBatch(): Promise<void> {
  const pending = latest?.upload_batch;
  if (!pending || terminal(pending.state)) return;
  await action(async () => {
    await invoke<BatchStatus>("cancel_upload_batch", {
      batchId: pending.batch_id,
    });
    offerRevision++;
  });
}
async function cancelOutgoingBatch(): Promise<void> {
  const pending = latest?.download_batch;
  if (!pending || terminal(pending.state)) return;
  await action(async () => {
    await invoke<BatchStatus>("cancel_download_batch", {
      batchId: pending.batch_id,
    });
    offerRevision++;
    notice("Lote cancelado. Los archivos pendientes no se enviarán.");
  });
}
async function acceptTransfer(t: Transfer): Promise<void> {
  await action(async () => {
    const destination =
      selectedDestination ??
      (await invoke<string | null>("choose_destination"));
    if (!destination) return;
    await invoke("accept_upload", { transferId: t.transfer_id, destination });
    selectedDestination = destination;
  });
}
type TransferView = { node: HTMLElement; update: (t: Transfer) => void };
const views = new Map<HTMLElement, Map<string, TransferView>>();
function transferView(detailed: boolean): TransferView {
  const card = node("article", "transfer-card");
  const heading = node("div", "transfer-title");
  const tile = node("span", "icon-tile");
  tile.append(icon("file"));
  const title = node("h3", "transfer-name");
  const state = node("span", "state");
  const headingText = node("div", "grow");
  headingText.append(title, state);
  heading.append(tile, headingText);
  card.append(heading);
  const progress = node("div", "progress");
  const bar = node("div");
  progress.append(bar);
  progress.setAttribute("role", "progressbar");
  progress.setAttribute("aria-label", "Avance de transferencia");
  progress.setAttribute("aria-valuemin", "0");
  progress.setAttribute("aria-valuemax", "100");
  const detail = node("p", "transfer-detail");
  const location = node("p", "transfer-detail");
  const error = node("p", "transfer-detail failed");
  const actions = node("div", "transfer-actions");
  card.append(progress, detail, location, error, actions);
  let actionSignature = "";
  return {
    node: card,
    update: (t) => {
      const done = BigInt(t.bytes_transferred);
      const size = BigInt(t.size_bytes);
      const percent =
        size === 0n
          ? t.state === "completed"
            ? 100
            : 0
          : Math.min(100, Number((done * 10000n) / size) / 100);
      title.textContent = t.file_name;
      state.textContent = stateLabel(t);
      state.classList.toggle("failed", t.state === "failed");
      bar.style.width = `${percent}%`;
      progress.setAttribute("aria-valuenow", String(percent));
      progress.setAttribute(
        "aria-valuetext",
        `${formatBytes(done)} de ${formatBytes(size)}`,
      );
      progress.hidden = terminal(t.state) || t.state === "awaiting_acceptance";
      const speed = speeds.get(t.transfer_id) ?? 0;
      const eta =
        speed > 0 ? Math.max(0, Math.ceil(Number(size - done) / speed)) : 0;
      detail.textContent = `${peerName(t.peer_id)} · ${formatBytes(done)} de ${formatBytes(size)}${t.state === "streaming" && speed > 0 ? ` · ${formatBytes(BigInt(Math.round(speed)))}/s · ${eta > 60 ? `${Math.ceil(eta / 60)} min` : `${eta} s`} restantes` : ""}`;
      location.hidden = !detailed || !t.destination;
      location.textContent =
        detailed && t.destination ? `Destino: ${t.destination}` : "";
      error.hidden = t.state !== "failed";
      error.textContent =
        t.state === "failed"
          ? (messages[t.error_code ?? ""] ??
            "La transferencia se interrumpió. Vuelve a enviarla.")
          : "";
      const inBatch = [latest?.upload_batch, latest?.download_batch].some(
        (manifest) => manifest?.files.some((f) => f.transfer_id === t.transfer_id),
      );
      const signature = `${t.transfer_id}/${t.state}/${Boolean(selectedDestination)}/${inBatch}`;
      if (signature !== actionSignature) {
        actionSignature = signature;
        actions.replaceChildren();
        if (!terminal(t.state) && !inBatch) {
          if (t.direction === "upload" && t.state === "awaiting_acceptance") {
            const accept = node(
              "button",
              "primary",
              selectedDestination ? "Aceptar" : "Aceptar y elegir carpeta",
            );
            accept.type = "button";
            accept.dataset.transferAction = "accept";
            accept.onclick = () => {
              void acceptTransfer(t);
            };
            actions.append(accept);
          }
          const cancel = node(
            "button",
            "",
            t.direction === "upload" && t.state === "awaiting_acceptance"
              ? "Rechazar"
              : "Cancelar",
          );
          cancel.type = "button";
          cancel.dataset.transferAction = "cancel";
          cancel.onclick = () => {
            if (batch?.transferId === t.transfer_id) {
              void cancelBatch();
            } else {
              void action(async () => {
                await invoke("cancel_transfer", { transferId: t.transfer_id });
              });
            }
          };
          actions.append(cancel);
        }
      }
      actions.hidden = terminal(t.state);
    },
  };
}
function renderTransfers(
  container: HTMLElement,
  transfers: Transfer[],
  detailed = false,
): void {
  let cache = views.get(container);
  if (!cache) {
    cache = new Map();
    views.set(container, cache);
  }
  const ids = new Set(transfers.map((t) => t.transfer_id));
  for (const [id, view] of cache)
    if (!ids.has(id)) {
      view.node.remove();
      cache.delete(id);
    }
  if (!transfers.length) {
    if (!container.querySelector(".empty-state")) {
      const empty = node("div", "panel empty-state");
      empty.append(
        node(
          "p",
          "",
          container.id === "current-transfer"
            ? "Sin transferencias en curso."
            : "Todavía no hay actividad.",
        ),
      );
      container.replaceChildren(empty);
    }
    return;
  }
  container.querySelector(".empty-state")?.remove();
  transfers.forEach((t, index) => {
    let view = cache.get(t.transfer_id);
    if (!view) {
      view = transferView(detailed);
      cache.set(t.transfer_id, view);
    }
    view.update(t);
    if (container.children[index] !== view.node)
      container.insertBefore(view.node, container.children[index] ?? null);
  });
}
function renderPeers(): void {
  if (!latest) return;
  const signature = JSON.stringify([latest.peers, selectedPeer]);
  if (signature === peerSignature) return;
  peerSignature = signature;
  const choices = $("device-choices");
  const peers = $("peers");
  choices.replaceChildren();
  peers.replaceChildren();
  if (!latest.peers.length) {
    choices.append(node("p", "", "Ningún dispositivo emparejado."));
    peers.append(
      node("p", "", "Todavía no has emparejado ningún dispositivo."),
    );
  }
  for (const peer of latest.peers) {
    const choice = node("button", "device-choice");
    choice.type = "button";
    choice.setAttribute("aria-pressed", String(peer.id === selectedPeer));
    const tile = node("span", "icon-tile");
    tile.append(icon("phone"));
    const label = node("span", "grow");
    label.append(
      node("span", "peer-name", peer.name),
      node("span", "peer-info", `Emparejado · ${peer.id.slice(0, 8)}`),
    );
    choice.append(tile, label);
    if (peer.id === selectedPeer) choice.append(icon("check"));
    choice.onclick = () => {
      selectedPeer = peer.id;
      renderPeers();
      updateControls();
    };
    choices.append(choice);
    const row = node("div", "peer-row");
    const info = node("div", "grow");
    info.append(
      node("div", "peer-name", peer.name),
      node("div", "peer-info", peer.id),
    );
    const revoke = node("button", "", "Revocar");
    revoke.type = "button";
    revoke.dataset.revoke = peer.id;
    revoke.setAttribute("aria-label", `Revocar ${peer.name}`);
    revoke.onclick = () => {
      void action(async () => {
        if (batch?.peerId === peer.id) discardBatch();
        await invoke("revoke_peer", { peerId: peer.id });
        notice(
          `${peer.name} ya no puede conectarse. Sus transferencias activas se han cancelado.`,
        );
      });
    };
    row.append(info, revoke);
    peers.append(row);
  }
}
function updateSpeeds(transfers: Transfer[]): void {
  const now = performance.now();
  const ids = new Set(transfers.map((t) => t.transfer_id));
  for (const id of samples.keys())
    if (!ids.has(id)) {
      samples.delete(id);
      speeds.delete(id);
    }
  for (const t of transfers) {
    const done = BigInt(t.bytes_transferred);
    const previous = samples.get(t.transfer_id);
    let speed = previous?.speed ?? 0;
    if (previous && now > previous.time && done > previous.bytes)
      speed = (Number(done - previous.bytes) * 1000) / (now - previous.time);
    else if (previous && now - previous.time > 2000) speed = 0;
    if (!previous || done !== previous.bytes)
      samples.set(t.transfer_id, { bytes: done, time: now, speed });
    speeds.set(t.transfer_id, speed);
  }
}
async function refresh(): Promise<void> {
  if (refreshing) return;
  refreshing = true;
  try {
    const revision = offerRevision;
    const snapshot = await invoke<Snapshot>("snapshot");
    if (revision !== offerRevision) return; // An offer completed after this snapshot was requested.
    const sessionId = snapshot.session?.session_id ?? null;
    // Only compare consecutive snapshots belonging to the same active session.
    const disappeared =
      sessionId && latest?.session?.session_id === sessionId
        ? latest.peers.filter(
            (peer) => !snapshot.peers.some((next) => next.id === peer.id),
          )
        : [];
    if (sessionId !== listSessionId) {
      if (batch && batch.sessionId !== sessionId) discardBatch();
      shownSendBatch = null;
      shownUploadBatch = null;
      shownDownloadBatch = null;
      selectedDestination = null;
      peerNames.clear();
      samples.clear();
      speeds.clear();
      hideDescriptor();
      listSessionId = sessionId;
    }
    latest = snapshot;
    for (const peer of snapshot.peers) peerNames.set(peer.id, peer.name);
    if (sessionId && snapshot.upload_batch) {
      if (
        !terminal(snapshot.upload_batch.state) ||
        (!shownSendBatch && !shownDownloadBatch) ||
        shownUploadBatch?.batch_id === snapshot.upload_batch.batch_id
      ) {
        shownUploadBatch = snapshot.upload_batch;
        if (!terminal(snapshot.upload_batch.state)) shownDownloadBatch = null;
      }
    }
    if (sessionId && snapshot.download_batch) {
      if (
        !terminal(snapshot.download_batch.state) ||
        (!shownSendBatch && !shownUploadBatch) ||
        shownDownloadBatch?.batch_id === snapshot.download_batch.batch_id
      ) {
        shownDownloadBatch = snapshot.download_batch;
        if (!terminal(snapshot.download_batch.state)) shownUploadBatch = null;
      }
    }
    const active = latest.session !== null;
    $("session-badge").textContent = active
      ? "Sesión activa"
      : "Sesión cerrada";
    $("session-badge").classList.toggle("active", active);
    $("session-setup").classList.toggle("hidden", active);
    $("session-address").textContent = latest.session
      ? `${latest.session.host}:${latest.session.port}`
      : "Sesión cerrada. Inicia una sesión desde la pantalla principal.";
    if (!active) {
      hideDescriptor();
      selectedDestination = null;
    }
    if (!latest.peers.some((peer) => peer.id === selectedPeer))
      selectedPeer = latest.peers[0]?.id ?? "";
    renderPeers();
    updateSpeeds(latest.transfers);
    const ordered = [...latest.transfers].reverse();
    const current = ordered.find((t) => !terminal(t.state)) ?? ordered[0];
    renderTransfers($("current-transfer"), current ? [current] : []);
    renderTransfers($("transfers"), ordered, true);
    const recent = ordered.find((t) => terminal(t.state));
    const recentContainer = $("recent-transfer");
    if (recent) {
      if (
        recentContainer.dataset.transferId !== recent.transfer_id ||
        recentContainer.dataset.state !== recent.state
      ) {
        const row = node("div", "row");
        const tile = node("span", "icon-tile");
        tile.append(icon(recent.state === "completed" ? "check" : "file"));
        const text = node("div", "grow");
        text.append(
          node("p", "transfer-name", recent.file_name),
          node("p", "transfer-detail", stateLabel(recent)),
        );
        row.append(tile, text);
        recentContainer.replaceChildren(row);
        recentContainer.dataset.transferId = recent.transfer_id;
        recentContainer.dataset.state = recent.state;
      }
    } else {
      recentContainer.replaceChildren(
        node("p", "", "Todavía no hay transferencias finalizadas."),
      );
      delete recentContainer.dataset.transferId;
    }
    if (shownSendBatch)
      for (const file of shownSendBatch.sources) {
        const transfer = latest.transfers.find(
          (t) => t.transfer_id === file.transfer_id,
        );
        if (transfer)
          Object.assign(file, {
            file_name: transfer.file_name,
            size_bytes: transfer.size_bytes,
            state: transfer.state,
          });
      }
    advanceBatch(latest);
    if (disappeared.length)
      notice(
        `${disappeared.map((peer) => peer.name).join(", ")} ${disappeared.length === 1 ? "se ha desconectado" : "se han desconectado"}. Para volver a conectar, muestra un nuevo QR y escanéalo en el móvil.`,
      );
    updateControls();
  } finally {
    refreshing = false;
  }
}
$("session-form").onsubmit = (event) => {
  event.preventDefault();
  void action(async () => {
    await invoke("start_session", {
      bindIpv4: $<HTMLInputElement>("bind-ip").value.trim(),
    });
    selectedDestination = null;
    notice("Sesión abierta. Muestra el QR para emparejar tu móvil.");
  });
};
$("stop-session").onclick = () => {
  void action(async () => {
    hideDescriptor();
    discardBatch();
    await invoke("stop_session");
    offerRevision++;
    shownSendBatch = null;
    shownUploadBatch = null;
    shownDownloadBatch = null;
    selectedPeer = "";
    selectedDestination = null;
    $<HTMLDialogElement>("settings-panel").close();
    notice(
      "Sesión cerrada. Los móviles necesitan un nuevo QR para volver a conectar. Las transferencias pendientes se han cancelado y se han eliminado sus parciales.",
    );
  });
};
$("receive").onclick = () => {
  void action(async () => {
    await invoke("set_receive_enabled", { enabled: !latest?.receive_enabled });
  });
};
$("choose-folder").onclick = () => {
  void action(async () => {
    const destination = await invoke<string | null>("choose_destination");
    if (destination) selectedDestination = destination;
  });
};
$("send-file").onclick = () => {
  void action(async () => {
    const peerId = selectedPeer;
    const sessionId = latest?.session?.session_id;
    if (
      !peerId || !sessionId || batch || offering ||
      incomingReserved() || outgoingReserved()
    ) return;
    const sources = await invoke<Source[] | null>("choose_sources");
    if (!sources?.length) return;
    if (
      latest?.session?.session_id !== sessionId ||
      !latest.peers.some((peer) => peer.id === peerId) ||
      incomingReserved() ||
      outgoingReserved() ||
      latest.transfers.some((t) => !terminal(t.state))
    )
      return;
    if (
      sources.length > 1 &&
      latest.peers.find((peer) => peer.id === peerId)?.supports_inbox
    ) {
      await invoke<BatchStatus>("offer_download_batch", {
        peerId,
        sources: sources.map((source) => source.source),
        sessionId,
      });
      offerRevision++;
      shownSendBatch = null;
      shownUploadBatch = null;
      notice("Oferta de lote enviada. Esperando aceptación en el móvil.");
      return;
    }
    shownDownloadBatch = null;
    batch = {
      peerId,
      sessionId,
      sources: sources.map((f) => ({
        ...f,
        transfer_id: null,
        state: "pending",
      })),
      index: 0,
      transferId: null,
      state: "accepted",
    };
    shownSendBatch = batch;
    shownUploadBatch = null;
    notice(
      `Lote preparado: ${sources.length} archivos. Confirma cada archivo en el móvil.`,
    );
  });
};
$("accept-batch").onclick = () => {
  void acceptIncomingBatch();
};
$("cancel-batch").onclick = () => {
  if (shownUploadBatch && !terminal(shownUploadBatch.state))
    void cancelIncomingBatch();
  else if (shownDownloadBatch && !terminal(shownDownloadBatch.state))
    void cancelOutgoingBatch();
  else void cancelBatch();
};
$("pair").onclick = () => {
  void action(async () => {
    hideDescriptor(); // Never leave credentials from an earlier window after an IPC or encoder failure.
    const generation = pairingGeneration;
    const until = performance.now() + 120000; // Include IPC time conservatively in the server window.
    const descriptor = await invoke<Descriptor>("open_pairing_window");
    if (generation !== pairingGeneration || performance.now() >= until) return;
    const payload = JSON.stringify(descriptor);
    descriptorUntil = until;
    $<HTMLTextAreaElement>("descriptor").value = payload;
    $("fingerprint").textContent =
      `Huella: ${descriptor.cert_sha256.match(/.{1,8}/g)?.join(" ")}`;
    try {
      const qr = encodePairingQr(payload);
      $<HTMLImageElement>("pair-qr").src = qr.dataUrl;
      $("pair-qr").classList.remove("hidden");
    } catch {
      $("qr-error").classList.remove("hidden");
      $<HTMLDetailsElement>("manual-pair").open = true;
    }
    $("pair-seconds").textContent = String(
      Math.max(0, Math.ceil((until - performance.now()) / 1000)),
    );
    showPanel("pair-panel");
  });
};
$("hide-pair").onclick = hideDescriptor;
$<HTMLDialogElement>("pair-panel").addEventListener("cancel", (event) => {
  event.preventDefault();
  hideDescriptor();
});
$("copy-pair").onclick = () => {
  void action(async () => {
    if (!descriptorUntil || performance.now() >= descriptorUntil) {
      hideDescriptor();
      return;
    }
    try {
      await navigator.clipboard.writeText(
        $<HTMLTextAreaElement>("descriptor").value,
      );
      notice("Datos copiados. Pégalos solo en tu Android.");
    } catch {
      $<HTMLTextAreaElement>("descriptor").select();
      notice("Selecciona Copiar o pulsa Ctrl+C para copiar los datos.");
    }
  });
};
$("open-activity").onclick = $("all-activity").onclick = () =>
  showPanel("activity-panel");
$("open-devices").onclick = () => showPanel("devices-panel");
$("open-settings").onclick = () => showPanel("settings-panel");
for (const button of document.querySelectorAll<HTMLButtonElement>(
  "[data-close]",
))
  button.onclick = () => {
    $<HTMLDialogElement>(button.dataset.close!).close();
  };
for (const id of panels)
  $<HTMLDialogElement>(id).addEventListener("close", () => {
    if (
      id === "pair-panel" &&
      descriptorUntil &&
      !$<HTMLDialogElement>(id).open
    )
      hideDescriptor();
    if (!panels.some((panel) => $<HTMLDialogElement>(panel).open)) markRail();
  });
window.addEventListener("pagehide", hideDescriptor);
setInterval(() => {
  if (descriptorUntil) {
    const left = Math.ceil((descriptorUntil - performance.now()) / 1000);
    if (left <= 0) hideDescriptor();
    else $("pair-seconds").textContent = String(left);
  }
  void refresh().catch((error) => {
    notice(
      messages[String(error)] ??
        "No se pudo actualizar el estado de la aplicación.",
      true,
    );
  });
}, 500);
void refresh().catch((error) => {
  notice(messages[String(error)] ?? "No se pudo iniciar la aplicación.", true);
});
