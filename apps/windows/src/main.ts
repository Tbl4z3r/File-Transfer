import { encodePairingQr } from "./pairing-qr.js";
type Peer = { id: string; name: string };
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
type Snapshot = {
  session: null | { host: string; port: number; cert_sha256: string };
  receive_enabled: boolean;
  peers: Peer[];
  transfers: Transfer[];
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
  invalid_request: "Comprueba la IPv4 del PC y el puerto (1024–65535).",
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
function markRail(id = "home"): void {
  for (const rail of ["home", ...Object.values(rails)]) {
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
  return latest?.peers.find((peer) => peer.id === id)?.name ?? "Dispositivo";
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
  $<HTMLInputElement>("port").disabled = active || busy;
  $<HTMLButtonElement>("pair").disabled = !active || busy;
  $<HTMLButtonElement>("receive").disabled = !active || busy;
  $<HTMLButtonElement>("choose-folder").disabled = !active || busy;
  $<HTMLButtonElement>("send-file").disabled =
    !active || !selectedPeer || hasPending || busy;
  $("receive").setAttribute(
    "aria-pressed",
    String(latest?.receive_enabled ?? false),
  );
  $("receive-label").textContent = latest?.receive_enabled
    ? "Dejar de recibir"
    : "Recibir";
  $("receive-setting").textContent = latest?.receive_enabled
    ? "Disponible para recibir solicitudes. Cada archivo requiere autorización."
    : "La recepción está desactivada.";
  $("action-hint").textContent = !active
    ? "Inicia una sesión para conectar el móvil."
    : !latest?.peers.length
      ? "Muestra el QR para emparejar un móvil."
      : hasPending
        ? "Termina o cancela la transferencia actual para enviar otra."
        : latest.receive_enabled
          ? "Disponible para recibir. Autoriza cada solicitud aquí."
          : "Elige un dispositivo para enviar o activa Recibir.";
  $("folder-label").textContent =
    selectedDestination ?? "Se elige al aceptar cada archivo";
  $("folder-hint").textContent = selectedDestination
    ? "Para las siguientes aceptaciones de esta sesión."
    : "Puedes elegir una carpeta para esta sesión.";
  $("choose-folder").textContent = selectedDestination ? "Cambiar" : "Elegir";
  for (const button of document.querySelectorAll<HTMLButtonElement>(
    "[data-transfer-action], [data-revoke]",
  ))
    button.disabled = busy;
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
      const signature = `${t.transfer_id}/${t.state}/${Boolean(selectedDestination)}`;
      if (signature !== actionSignature) {
        actionSignature = signature;
        actions.replaceChildren();
        if (!terminal(t.state)) {
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
            void action(async () => {
              await invoke("cancel_transfer", { transferId: t.transfer_id });
            });
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
    latest = await invoke<Snapshot>("snapshot");
    const active = latest.session !== null;
    $("session-badge").textContent = active
      ? "Sesión activa"
      : "Sesión cerrada";
    $("session-badge").classList.toggle("active", active);
    $("session-setup").classList.toggle("hidden", active);
    $("session-address").textContent = latest.session
      ? `${latest.session.host}:${latest.session.port}`
      : "Sesión cerrada. Inicia una sesión desde Inicio.";
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
      port: Number($<HTMLInputElement>("port").value),
    });
    selectedDestination = null;
    notice("Sesión abierta. Muestra el QR para emparejar tu móvil.");
  });
};
$("stop-session").onclick = () => {
  void action(async () => {
    hideDescriptor();
    await invoke("stop_session");
    selectedDestination = null;
    $<HTMLDialogElement>("settings-panel").close();
    notice(
      "Sesión cerrada. Las transferencias pendientes se han cancelado y se han eliminado sus parciales.",
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
    if (!peerId) return;
    const source = await invoke<string | null>("choose_source");
    if (!source) return;
    await invoke("offer_download", { peerId, source });
    notice("Oferta enviada. Confirma la recepción en el móvil.");
  });
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
$("home").onclick = () => {
  markRail();
  $("home-content").focus();
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
