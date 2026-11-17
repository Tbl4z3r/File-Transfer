import { encodePairingQr } from './pairing-qr.js';
type Peer = { id: string; name: string };
type Transfer = { transfer_id: string; direction: 'upload' | 'download'; file_name: string; size_bytes: string; bytes_transferred: string; state: string; error_code?: string; sha256?: string; peer_id: string; destination: string | null };
type Snapshot = { session: null | { host: string; port: number; cert_sha256: string }; receive_enabled: boolean; peers: Peer[]; transfers: Transfer[] };
type Descriptor = { cert_sha256: string; pairing_token: string; [key: string]: unknown };
declare global { interface Window { __TAURI__?: { core: { invoke<T>(command: string, args?: Record<string, unknown>): Promise<T> } } } }
const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;
const invoke = <T>(command: string, args?: Record<string, unknown>): Promise<T> => {
  if (!window.__TAURI__) return Promise.reject('desktop_required');
  return window.__TAURI__.core.invoke<T>(command, args);
};
const messages: Record<string, string> = {
  invalid_request: 'Comprueba la IPv4 del PC y el puerto (1024–65535).', busy: 'Termina o cancela la transferencia actual antes de iniciar otra.',
  io_error: 'No se pudo acceder al archivo o abrir la conexión. Comprueba la carpeta, los permisos y que la IPv4 pertenezca a este PC.',
  insufficient_space: 'No hay espacio suficiente en el destino. Elige otra carpeta o libera espacio.', source_changed: 'El archivo de origen ha cambiado. Vuelve a seleccionarlo y enviarlo.',
  invalid_name: 'El nombre contiene caracteres que Windows no admite. Cambia su nombre antes de enviarlo.', invalid_state: 'La transferencia ya cambió de estado. Revisa su resultado.',
  session_closed: 'Inicia una sesión para conectar el móvil.', revoked: 'El dispositivo fue revocado. Vuelve a emparejarlo para conectar.',
  timeout: 'La conexión no avanzó a tiempo. Comprueba la red y vuelve a enviar el archivo.', network_error: 'La conexión se interrumpió. Vuelve a enviar el archivo desde el principio.',
  size_mismatch: 'El archivo llegó incompleto. Vuelve a enviarlo.', integrity_mismatch: 'El archivo no superó la verificación. Vuelve a enviarlo.',
  desktop_required: 'Abre esta pantalla desde la aplicación de escritorio File Transfer.', storage_in_use: 'File Transfer ya está usando sus datos. Cierra la otra instancia.'
};
let selectedSource: string | null = null;
let latest: Snapshot | null = null;
let peerSignature = '';
let descriptorUntil = 0;
let pairingGeneration = 0;
let refreshing = false;
let busy = false;
const samples = new Map<string, { bytes: bigint; time: number; speed: number }>();
function notice(message: string, error = false): void {
  const element = $('notice'); element.textContent = message; element.classList.remove('hidden'); element.classList.toggle('error', error);
}
async function action(run: () => Promise<void>): Promise<void> {
  if (busy) return; busy = true;
  try { await run(); await refresh(); } catch (error) { notice(messages[String(error)] ?? 'No se pudo completar la operación. Comprueba la conexión y vuelve a intentarlo.', true); }
  finally { busy = false; }
}
function formatBytes(value: bigint): string {
  const n = Number(value); if (n < 1024) return `${n} B`;
  const unit = Math.min(Math.floor(Math.log(n) / Math.log(1024)), 4);
  return `${(n / 1024 ** unit).toLocaleString('es', { maximumFractionDigits: 1 })} ${['B','KiB','MiB','GiB','TiB'][unit]}`;
}
function terminal(state: string): boolean { return ['completed','failed','cancelled'].includes(state); }
function hideDescriptor(): void {
  pairingGeneration++;
  descriptorUntil = 0;
  $<HTMLImageElement>('pair-qr').removeAttribute('src');
  $('pair-qr').classList.add('hidden');
  $('qr-error').classList.add('hidden');
  $('pair-panel').classList.add('hidden');
  $<HTMLTextAreaElement>('descriptor').value = '';
  $('fingerprint').textContent = '';
}
function showTransfer(t: Transfer): HTMLElement {
  const card = document.createElement('article'); card.className = 'transfer-card';
  const row = document.createElement('div'); row.className = 'row';
  const name = document.createElement('span'); name.className = 'transfer-name'; name.textContent = `${t.direction === 'upload' ? '↓' : '↑'} ${t.file_name}`;
  const state = document.createElement('span'); state.className = `state ${t.state === 'failed' ? 'failed' : ''}`;
  const labels: Record<string,string> = { awaiting_acceptance: t.direction === 'upload' ? 'Requiere tu autorización' : 'Esperando al móvil', ready: 'Preparado', streaming: 'Transfiriendo', awaiting_verification: 'Verificando integridad', completed: 'Completado', failed: 'Interrumpido', cancelled: t.error_code === 'revoked' ? 'Dispositivo revocado' : 'Cancelado' };
  state.textContent = labels[t.state] ?? t.state; row.append(name,state); card.append(row);
  const done = BigInt(t.bytes_transferred); const size = BigInt(t.size_bytes);
  const progress = document.createElement('div'); progress.className = 'progress';
  const bar = document.createElement('div'); bar.style.width = `${size === 0n ? (t.state === 'completed' ? 100 : 0) : Number(done * 10000n / size) / 100}%`; progress.append(bar); card.append(progress);
  const detail = document.createElement('div'); detail.className = 'transfer-detail';
  const peerName = latest?.peers.find(p => p.id === t.peer_id)?.name ?? 'Dispositivo';
  const now = Date.now(); const previous = samples.get(t.transfer_id); let speed = previous?.speed ?? 0;
  if (previous && now > previous.time && done > previous.bytes) speed = Number(done - previous.bytes) * 1000 / (now - previous.time);
  samples.set(t.transfer_id,{ bytes:done,time:now,speed });
  const eta = speed > 0 ? Math.ceil(Number(size - done) / speed) : 0;
  detail.textContent = `${peerName} · ${formatBytes(done)} de ${formatBytes(size)}${t.state === 'streaming' && speed > 0 ? ` · ${formatBytes(BigInt(Math.round(speed)))}/s · ${eta > 60 ? `${Math.ceil(eta / 60)} min` : `${eta} s`} restantes` : ''}`;
  card.append(detail);
  if (t.destination) { const location = document.createElement('p'); location.className = 'transfer-detail'; location.textContent = `Guardado en ${t.destination}`; card.append(location); }
  if (t.error_code && t.state === 'failed') { const error = document.createElement('p'); error.className = 'transfer-detail failed'; error.textContent = messages[t.error_code] ?? 'La transferencia se interrumpió. Vuelve a enviarla.'; card.append(error); }
  if (!terminal(t.state)) {
    const actions = document.createElement('div'); actions.className = 'transfer-actions';
    if (t.direction === 'upload' && t.state === 'awaiting_acceptance') {
      const accept = document.createElement('button'); accept.className = 'primary'; accept.textContent = 'Aceptar y elegir carpeta';
      accept.onclick = () => { void action(async () => { const destination = await invoke<string | null>('choose_destination'); if (destination) await invoke('accept_upload',{ transferId:t.transfer_id,destination }); }); }; actions.append(accept);
    }
    const cancel = document.createElement('button'); cancel.textContent = t.state === 'awaiting_acceptance' && t.direction === 'upload' ? 'Rechazar' : 'Cancelar';
    cancel.onclick = () => { void action(async () => { await invoke('cancel_transfer',{ transferId:t.transfer_id }); }); }; actions.append(cancel); card.append(actions);
  }
  return card;
}
async function refresh(): Promise<void> {
  if (refreshing) return; refreshing = true;
  try {
    latest = await invoke<Snapshot>('snapshot'); const active = latest.session !== null;
    $('session-badge').textContent = active ? '● Sesión activa' : 'Sesión cerrada'; $('session-badge').classList.toggle('active',active);
    $('start-session').classList.toggle('hidden',active); $('stop-session').classList.toggle('hidden',!active);
    $<HTMLInputElement>('bind-ip').disabled = active; $<HTMLInputElement>('port').disabled = active;
    $<HTMLInputElement>('receive').disabled = !active; $<HTMLInputElement>('receive').checked = latest.receive_enabled;
    $<HTMLButtonElement>('pair').disabled = !active;
    const hasPending = latest.transfers.some(t => !terminal(t.state));
    $<HTMLButtonElement>('send-file').disabled = !active || !selectedSource || !$<HTMLSelectElement>('peer-select').value || hasPending;
    if (!active) hideDescriptor();
    const signature = JSON.stringify(latest.peers);
    if (signature !== peerSignature) {
      peerSignature = signature; const select = $<HTMLSelectElement>('peer-select'); const selected = select.value; select.replaceChildren();
      const placeholder = document.createElement('option'); placeholder.value = ''; placeholder.textContent = latest.peers.length ? 'Elige un dispositivo' : 'Empareja un dispositivo primero'; select.append(placeholder);
      for (const peer of latest.peers) { const option = document.createElement('option'); option.value = peer.id; option.textContent = `${peer.name} · ${peer.id.slice(0,8)}`; select.append(option); }
      if (latest.peers.some(p => p.id === selected)) select.value = selected;
      const peers = $('peers'); peers.replaceChildren();
      if (!latest.peers.length) { const p = document.createElement('p'); p.className = 'subtle'; p.textContent = 'Todavía no has emparejado ningún dispositivo.'; peers.append(p); }
      for (const peer of latest.peers) {
        const row = document.createElement('div'); row.className = 'peer-row'; const name = document.createElement('span'); name.textContent = `${peer.name} · ${peer.id.slice(0,8)}`;
        const revoke = document.createElement('button'); revoke.textContent = 'Revocar'; revoke.onclick = () => { void action(async () => { await invoke('revoke_peer',{ peerId:peer.id }); notice(`${peer.name} ya no puede conectarse. Sus transferencias activas se han cancelado.`); }); }; row.append(name,revoke); peers.append(row);
      }
    }
    const transfers = $('transfers');
    if (latest.transfers.length) transfers.replaceChildren(...[...latest.transfers].reverse().map(showTransfer));
    else if (!transfers.querySelector('.empty-state')) {
      const empty = document.createElement('div'); empty.className = 'empty-state'; const title = document.createElement('h3'); title.textContent = 'Todo listo para compartir'; const p = document.createElement('p'); p.textContent = 'Tus transferencias aparecerán aquí.'; empty.append(title,p); transfers.replaceChildren(empty);
    }
  } finally { refreshing = false; }
}
$('session-form').onsubmit = event => { event.preventDefault(); void action(async () => { await invoke('start_session',{ bindIpv4:$<HTMLInputElement>('bind-ip').value.trim(),port:Number($<HTMLInputElement>('port').value) }); notice('Sesión abierta. Ahora puedes emparejar el móvil y enviar o recibir archivos.'); }); };
$('stop-session').onclick = () => { void action(async () => { hideDescriptor(); await invoke('stop_session'); notice('Sesión cerrada. Las transferencias pendientes se han cancelado y se han eliminado sus parciales.'); }); };
$('receive').onchange = () => { void action(async () => { await invoke('set_receive_enabled',{ enabled:$<HTMLInputElement>('receive').checked }); }); };
$('peer-select').onchange = () => { void refresh(); };
$('choose-file').onclick = () => { void action(async () => { const source = await invoke<string | null>('choose_source'); if (source) { selectedSource = source; $('selected-file').textContent = source.split(/[\\/]/).pop() ?? source; } }); };
$('send-file').onclick = () => { void action(async () => { await invoke('offer_download',{ peerId:$<HTMLSelectElement>('peer-select').value,source:selectedSource }); notice('Oferta enviada. Confirma la recepción en el móvil.'); }); };
$('pair').onclick = () => { void action(async () => {
  hideDescriptor(); // Clear any earlier token/QR before an invocation or encoder can fail.
  const generation = pairingGeneration;
  const until = performance.now() + 120000; // Conservative: include IPC time in the server window.
  const descriptor = await invoke<Descriptor>('open_pairing_window');
  if (generation !== pairingGeneration || performance.now() >= until) return;
  const payload = JSON.stringify(descriptor);
  descriptorUntil = until;
  $<HTMLTextAreaElement>('descriptor').value = payload;
  $('fingerprint').textContent = `Huella: ${descriptor.cert_sha256.match(/.{1,8}/g)?.join(' ')}`;
  try {
    const qr = encodePairingQr(payload);
    $<HTMLImageElement>('pair-qr').src = qr.dataUrl;
    $('pair-qr').classList.remove('hidden');
  } catch {
    // Current descriptor remains available for manual entry; never retain an older image.
    $('qr-error').classList.remove('hidden');
  }
  $('pair-panel').classList.remove('hidden');
  $('pair-seconds').textContent = String(Math.max(0, Math.ceil((until - performance.now()) / 1000)));
  $('pair-panel').scrollIntoView({ block: 'start' });
}); };
$('hide-pair').onclick = hideDescriptor;
$('copy-pair').onclick = () => { void action(async () => { if (!descriptorUntil || performance.now() >= descriptorUntil) { hideDescriptor(); return; } try { await navigator.clipboard.writeText($<HTMLTextAreaElement>('descriptor').value); notice('Datos copiados. Pégalos solo en tu Android.'); } catch { $<HTMLTextAreaElement>('descriptor').select(); notice('Selecciona Copiar o pulsa Ctrl+C para copiar los datos.'); } }); };
setInterval(() => {
  if (descriptorUntil) { const left = Math.ceil((descriptorUntil - performance.now()) / 1000); if (left <= 0) hideDescriptor(); else $('pair-seconds').textContent = String(left); }
  void refresh().catch(error => { notice(messages[String(error)] ?? 'No se pudo actualizar el estado de la aplicación.',true); });
},500);
void refresh().catch(error => { notice(messages[String(error)] ?? 'No se pudo iniciar la aplicación.',true); });
