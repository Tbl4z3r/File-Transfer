mod download_batch;
pub mod model;
mod server;
mod store;
mod upload_batch;
pub use upload_batch::{BatchState, BatchStatus, LocalUploadBatch};

use base64::{
    Engine as _,
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
};
use fs2::FileExt;
use model::*;
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    fs::{self, File, OpenOptions},
    net::Ipv4Addr,
    path::{Path, PathBuf},
    sync::{Arc, Mutex, MutexGuard, atomic::AtomicBool},
    time::{Duration, Instant, SystemTime},
};
use subtle::ConstantTimeEq;
use tempfile::NamedTempFile;
use tokio_util::sync::CancellationToken;

pub use server::SessionInfo;

#[derive(Clone, Serialize)]
pub struct Peer {
    pub supports_inbox: bool,
    pub id: String,
    pub name: String,
}
#[derive(Clone, Serialize)]
pub struct LocalTransfer {
    #[serde(flatten)]
    pub transfer: Transfer,
    pub peer_id: String,
    pub destination: Option<PathBuf>,
    pub delivery_uncertain: bool,
}
#[derive(Serialize)]
pub struct Snapshot {
    pub session: Option<SessionInfo>,
    pub receive_enabled: bool,
    pub peers: Vec<Peer>,
    pub transfers: Vec<LocalTransfer>,
    pub upload_batch: Option<LocalUploadBatch>,
    pub download_batch: Option<LocalUploadBatch>,
}
#[derive(Clone, Copy)]
pub struct Timeouts {
    pub pairing: Duration,
    pub waiting: Duration,
    pub verification: Duration,
    pub idle: Duration,
    pub headers: Duration,
}
impl Default for Timeouts {
    fn default() -> Self {
        Self {
            pairing: Duration::from_secs(120),
            waiting: Duration::from_secs(120),
            verification: Duration::from_secs(120),
            idle: Duration::from_secs(60),
            headers: Duration::from_secs(15),
        }
    }
}
struct PairWindow {
    token_hash: [u8; 32],
    opened: Instant,
    attempts: u8,
}
#[derive(Clone, Serialize, serde::Deserialize, PartialEq)]
pub struct SourceStamp {
    pub size_bytes: String,
    pub modified: String,
}
impl SourceStamp {
    pub fn new(len: u64, modified: SystemTime) -> Self {
        let modified = match modified.duration_since(SystemTime::UNIX_EPOCH) {
            Ok(duration) => duration.as_nanos().to_string(),
            Err(error) => format!("-{}", error.duration().as_nanos()),
        };
        Self {
            size_bytes: len.to_string(),
            modified,
        }
    }
    fn matches(&self, source: &Source) -> bool {
        *self == Self::new(source.len, source.modified)
    }
}
#[derive(Clone)]
struct Source {
    path: PathBuf,
    len: u64,
    modified: SystemTime,
}
struct Record {
    transfer: Transfer,
    peer_id: String,
    changed: Instant,
    progressed: Instant,
    cancel: CancellationToken,
    temp: Option<NamedTempFile>,
    directory: Option<PathBuf>,
    published: Option<PathBuf>,
    source: Option<Source>,
    final_bytes_released: bool,
}
struct ActiveSession {
    info: SessionInfo,
    cancel: CancellationToken,
    closed: Arc<AtomicBool>,
}
#[derive(Clone)]
struct AuthenticatedPeer {
    id: String,
    credential_hash: String,
}
struct Inner {
    registry: store::Registry,
    window: Option<PairWindow>,
    records: VecDeque<Record>,
    session: Option<ActiveSession>,
    closing: Option<Arc<AtomicBool>>,
    receive: bool,
    upload_batch: Option<upload_batch::SessionBatch>,
    download_batch: Option<upload_batch::SessionBatch>,
    peer_activity: HashMap<String, Instant>,
    inbox_peers: HashSet<String>,
}
pub struct Engine {
    directory: PathBuf,
    identity: store::Identity,
    cert: Vec<u8>,
    key: Vec<u8>,
    inner: Mutex<Inner>,
    timeouts: Timeouts,
    ephemeral_peers: bool,
    _storage_lock: File,
}
fn random_secret() -> Result<String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).map_err(|_| Fault::new(500, "io_error"))?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}
fn hash_secret(secret: &str) -> [u8; 32] {
    Sha256::digest(secret.as_bytes()).into()
}
fn valid_secret(secret: &str) -> bool {
    secret.len() == 43
        && URL_SAFE_NO_PAD
            .decode(secret)
            .is_ok_and(|v| v.len() == 32 && URL_SAFE_NO_PAD.encode(&v) == secret)
}

impl Engine {
    pub fn open(directory: impl AsRef<Path>, server_name: &str) -> Result<Arc<Self>> {
        Self::open_with_timeouts(directory, server_name, Timeouts::default())
    }
    pub fn open_with_timeouts(
        directory: impl AsRef<Path>,
        server_name: &str,
        timeouts: Timeouts,
    ) -> Result<Arc<Self>> {
        Self::open_configured(directory.as_ref(), server_name, timeouts, false)
    }
    /// Desktop credentials exist only in this running session; identity and partial journal persist.
    pub fn open_ephemeral(directory: impl AsRef<Path>, server_name: &str) -> Result<Arc<Self>> {
        Self::open_ephemeral_with_timeouts(directory, server_name, Timeouts::default())
    }
    pub fn open_ephemeral_with_timeouts(
        directory: impl AsRef<Path>,
        server_name: &str,
        timeouts: Timeouts,
    ) -> Result<Arc<Self>> {
        Self::open_configured(directory.as_ref(), server_name, timeouts, true)
    }
    fn open_configured(
        directory: &Path,
        server_name: &str,
        timeouts: Timeouts,
        ephemeral_peers: bool,
    ) -> Result<Arc<Self>> {
        store::private_directory(directory).map_err(|e| io_fault(&e))?;
        let directory = fs::canonicalize(directory).map_err(|e| io_fault(&e))?;
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(directory.join("instance.lock"))
            .map_err(|e| io_fault(&e))?;
        lock.try_lock_exclusive()
            .map_err(|_| Fault::new(409, "storage_in_use"))?;
        let identity = store::identity(&directory, server_name)?;
        let cert = STANDARD
            .decode(&identity.cert)
            .map_err(|_| Fault::new(500, "identity_invalid"))?;
        let key = STANDARD
            .decode(&identity.key)
            .map_err(|_| Fault::new(500, "identity_invalid"))?;
        let mut registry = store::registry(&directory)?;
        store::clean_orphans(&mut registry)?;
        if ephemeral_peers {
            registry.peers.clear();
        }
        store::save(&directory.join("peers.json"), &registry).map_err(|e| io_fault(&e))?;
        Ok(Arc::new(Self {
            directory,
            identity,
            cert,
            key,
            inner: Mutex::new(Inner {
                registry,
                window: None,
                records: VecDeque::new(),
                session: None,
                closing: None,
                receive: false,
                upload_batch: None,
                download_batch: None,
                peer_activity: HashMap::new(),
                inbox_peers: HashSet::new(),
            }),
            timeouts,
            ephemeral_peers,
            _storage_lock: lock,
        }))
    }
    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().expect("engine mutex poisoned")
    }
    fn save_registry(&self, registry: &store::Registry) -> Result<()> {
        if self.ephemeral_peers {
            let mut journal = registry.clone();
            journal.peers.clear();
            store::save(&self.directory.join("peers.json"), &journal).map_err(|e| io_fault(&e))
        } else {
            store::save(&self.directory.join("peers.json"), registry).map_err(|e| io_fault(&e))
        }
    }
    pub fn certificate_der(&self) -> &[u8] {
        &self.cert
    }
    pub fn server_device_id(&self) -> &str {
        &self.identity.id
    }
    pub fn cert_sha256(&self) -> String {
        digest(&self.cert)
    }
    pub async fn start_session(self: &Arc<Self>, bind: Ipv4Addr, port: u16) -> Result<SessionInfo> {
        server::start(self.clone(), bind, port, false).await
    }
    /// Desktop mode keeps the familiar port whenever available, otherwise lets the OS bind one.
    pub async fn start_session_auto(self: &Arc<Self>, bind: Ipv4Addr) -> Result<SessionInfo> {
        server::start(self.clone(), bind, 51337, true).await
    }
    pub fn stop_session(&self) {
        let mut inner = self.lock();
        if let Some(session) = inner.session.take() {
            inner.closing = Some(session.closed.clone());
            session.cancel.cancel();
        }
        inner.window = None;
        inner.receive = false;
        let ids: Vec<String> = inner
            .records
            .iter()
            .filter(|r| !r.transfer.state.terminal())
            .map(|r| r.transfer.transfer_id.clone())
            .collect();
        for id in ids {
            self.terminate(&mut inner, &id, TransferState::Cancelled, "session_closed");
        }
        inner.records.clear();
        inner.upload_batch = None;
        inner.download_batch = None;
        if self.ephemeral_peers {
            inner.registry.peers.clear();
        }
        inner.peer_activity.clear();
        inner.inbox_peers.clear();
    }
    pub fn open_pairing_window(&self) -> Result<Descriptor> {
        let mut inner = self.lock();
        let info = inner
            .session
            .as_ref()
            .ok_or(Fault::new(409, "session_closed"))?
            .info
            .clone();
        let token = random_secret()?;
        inner.window = Some(PairWindow {
            token_hash: hash_secret(&token),
            opened: Instant::now(),
            attempts: 0,
        });
        Ok(Descriptor {
            v: 1,
            host: info.host,
            port: info.port,
            server_device_id: self.identity.id.clone(),
            cert_sha256: self.cert_sha256(),
            pairing_token: token,
        })
    }
    pub fn set_receive_enabled(&self, enabled: bool) -> Result<()> {
        let mut inner = self.lock();
        if inner.session.is_none() {
            return Err(Fault::new(409, "session_closed"));
        }
        inner.receive = enabled;
        Ok(())
    }
    pub fn list_peers(&self) -> Vec<Peer> {
        self.snapshot().peers
    }
    pub fn revoke_peer(&self, id: &str) -> Result<()> {
        let mut inner = self.lock();
        self.revoke_peer_locked(&mut inner, id, "revoked")
    }
    fn revoke_peer_locked(&self, inner: &mut Inner, id: &str, code: &str) -> Result<()> {
        let before = inner.registry.clone();
        inner.registry.peers.retain(|p| p.id != id);
        if !self.ephemeral_peers
            && let Err(error) = self.save_registry(&inner.registry)
        {
            inner.registry = before;
            return Err(error);
        }
        inner.peer_activity.remove(id);
        inner.inbox_peers.remove(id);
        let ids: Vec<_> = inner
            .records
            .iter()
            .filter(|r| r.peer_id == id && !r.transfer.state.terminal())
            .map(|r| r.transfer.transfer_id.clone())
            .collect();
        for transfer in ids {
            self.terminate(inner, &transfer, TransferState::Cancelled, code);
        }
        if inner
            .upload_batch
            .as_ref()
            .is_some_and(|b| b.local.peer_id == id)
        {
            self.stop_upload_batch_locked(inner, BatchState::Cancelled, code);
        }
        if inner
            .download_batch
            .as_ref()
            .is_some_and(|b| b.local.peer_id == id)
        {
            self.stop_download_batch_locked(inner, BatchState::Cancelled, code);
        }
        Ok(())
    }
    fn disconnect(&self, peer: &AuthenticatedPeer) -> Result<()> {
        let mut inner = self.lock();
        Self::ensure_authenticated(&inner, peer)?;
        self.revoke_peer_locked(&mut inner, &peer.id, "revoked")
    }
    fn pair(&self, request: PairRequest, supports_inbox: bool) -> Result<PairResponse> {
        if !valid_id(&request.device_id) || !valid_device_name(&request.device_name) {
            return Err(Fault::new(400, "invalid_request"));
        }
        let mut inner = self.lock();
        let window = inner
            .window
            .as_mut()
            .ok_or(Fault::new(401, "pairing_denied"))?;
        let allowed = window.opened.elapsed() < self.timeouts.pairing
            && window.attempts < 5
            && valid_secret(&request.pairing_token)
            && bool::from(
                window
                    .token_hash
                    .ct_eq(&hash_secret(&request.pairing_token)),
            );
        if !allowed {
            window.attempts += 1;
            if window.attempts >= 5 || window.opened.elapsed() >= self.timeouts.pairing {
                inner.window = None;
            }
            return Err(Fault::new(401, "pairing_denied"));
        }
        if inner
            .registry
            .peers
            .iter()
            .any(|p| p.id == request.device_id)
        {
            return Err(Fault::new(409, "already_paired"));
        }
        let credential = random_secret()?;
        inner.registry.peers.push(store::PeerRecord {
            id: request.device_id.clone(),
            name: request.device_name,
            credential_hash: digest(credential.as_bytes()),
        });
        if !self.ephemeral_peers
            && let Err(error) = self.save_registry(&inner.registry)
        {
            inner.registry.peers.pop();
            return Err(error);
        }
        if supports_inbox {
            inner.inbox_peers.insert(request.device_id.clone());
        }
        inner
            .peer_activity
            .insert(request.device_id, Instant::now());
        inner.window = None;
        Ok(PairResponse {
            protocol_version: 1,
            server_device_id: self.identity.id.clone(),
            server_name: self.identity.name.clone(),
            credential,
        })
    }
    fn authenticate(&self, id: &str, credential: &str) -> Result<AuthenticatedPeer> {
        if !valid_id(id) || !valid_secret(credential) {
            return Err(Fault::new(401, "unauthorized"));
        }
        let candidate = digest(credential.as_bytes());
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        if inner.registry.peers.iter().any(|p| {
            p.id == id && bool::from(p.credential_hash.as_bytes().ct_eq(candidate.as_bytes()))
        }) {
            inner.peer_activity.insert(id.into(), Instant::now());
            Ok(AuthenticatedPeer {
                id: id.into(),
                credential_hash: candidate,
            })
        } else {
            Err(Fault::new(401, "unauthorized"))
        }
    }
    fn ensure_peer(inner: &Inner, peer: &str) -> Result<()> {
        if inner.registry.peers.iter().any(|p| p.id == peer) {
            Ok(())
        } else {
            Err(Fault::new(401, "unauthorized"))
        }
    }
    fn ensure_authenticated(inner: &Inner, peer: &AuthenticatedPeer) -> Result<()> {
        if inner.registry.peers.iter().any(|p| {
            p.id == peer.id
                && bool::from(
                    p.credential_hash
                        .as_bytes()
                        .ct_eq(peer.credential_hash.as_bytes()),
                )
        }) {
            Ok(())
        } else {
            Err(Fault::new(401, "unauthorized"))
        }
    }
    fn record_index(inner: &Inner, peer: &AuthenticatedPeer, id: &str) -> Result<usize> {
        Self::ensure_authenticated(inner, peer)?;
        inner
            .records
            .iter()
            .position(|r| r.peer_id == peer.id && r.transfer.transfer_id == id)
            .ok_or(Fault::new(404, "not_found"))
    }
    fn expire_locked(&self, inner: &mut Inner) {
        if self.ephemeral_peers {
            let expired: Vec<_> = inner
                .peer_activity
                .iter()
                .filter(|(id, at)| {
                    inner.inbox_peers.contains(*id) && at.elapsed() >= Duration::from_secs(45)
                })
                .map(|(id, _)| id.clone())
                .collect();
            for id in expired {
                let _ = self.revoke_peer_locked(inner, &id, "revoked");
            }
        }
        self.expire_batches_locked(inner);
        if inner
            .window
            .as_ref()
            .is_some_and(|w| w.opened.elapsed() >= self.timeouts.pairing)
        {
            inner.window = None;
        }
        let expired: Vec<_> = inner
            .records
            .iter()
            .filter_map(|r| {
                let limit = match r.transfer.state {
                    TransferState::AwaitingAcceptance | TransferState::Ready => {
                        self.timeouts.waiting
                    }
                    TransferState::AwaitingVerification => self.timeouts.verification,
                    TransferState::Streaming => self.timeouts.idle,
                    _ => return None,
                };
                let clock = if r.transfer.state == TransferState::Streaming {
                    r.progressed
                } else {
                    r.changed
                };
                (clock.elapsed() >= limit)
                    .then(|| (r.transfer.transfer_id.clone(), r.transfer.state))
            })
            .collect();
        for (id, state) in expired {
            let (terminal, code) = if state == TransferState::AwaitingAcceptance {
                (TransferState::Cancelled, "cancelled")
            } else {
                (TransferState::Failed, "timeout")
            };
            self.terminate(inner, &id, terminal, code);
        }
    }
    pub fn expire(&self) {
        self.expire_locked(&mut self.lock());
    }
    fn terminate(&self, inner: &mut Inner, id: &str, state: TransferState, code: &str) {
        if let Some(record) = inner
            .records
            .iter_mut()
            .find(|r| r.transfer.transfer_id == id)
        {
            if record.transfer.state.terminal() {
                return;
            }
            record.cancel.cancel();
            record.transfer.state = state;
            record.transfer.error_code = Some(code.into());
            record.transfer.sha256 = None;
            record.changed = Instant::now();
            if let Some(temp) = record.temp.take() {
                inner.registry.partials.retain(|p| p != temp.path());
                drop(temp);
                // A stale journal entry is harmless; startup only cleans exact owned temp names.
                let _ = self.save_registry(&inner.registry);
            }
        }
        Self::sync_batches_locked(inner);
    }
    fn new_record(
        inner: &mut Inner,
        peer: &str,
        name: String,
        size: u64,
        direction: Direction,
        source: Option<Source>,
        batch_entry: bool,
    ) -> Result<Transfer> {
        if inner.session.is_none() {
            return Err(Fault::new(409, "session_closed"));
        }
        Self::ensure_peer(inner, peer)?;
        if (!batch_entry && Self::batch_busy(inner))
            || inner.records.iter().any(|r| !r.transfer.state.terminal())
        {
            return Err(Fault::new(409, "busy"));
        }
        while inner.records.len() >= 32 {
            if let Some(index) = inner
                .records
                .iter()
                .position(|r| r.transfer.state.terminal())
            {
                inner.records.remove(index);
            } else {
                break;
            }
        }
        let transfer = Transfer {
            transfer_id: new_id(),
            direction,
            file_name: name,
            size_bytes: size.to_string(),
            state: TransferState::AwaitingAcceptance,
            bytes_transferred: "0".into(),
            sha256: None,
            error_code: None,
        };
        inner.records.push_back(Record {
            transfer: transfer.clone(),
            peer_id: peer.into(),
            changed: Instant::now(),
            progressed: Instant::now(),
            cancel: CancellationToken::new(),
            temp: None,
            directory: None,
            published: None,
            source,
            final_bytes_released: false,
        });
        Ok(transfer)
    }
    fn create_upload(&self, peer: &AuthenticatedPeer, request: CreateUpload) -> Result<Transfer> {
        if !valid_name(&request.file_name) {
            return Err(Fault::new(400, "invalid_name"));
        }
        let size = parse_size(&request.size_bytes).ok_or(Fault::new(400, "invalid_request"))?;
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::ensure_authenticated(&inner, peer)?;
        if !inner.receive {
            return Err(Fault::new(409, "receive_disabled"));
        }
        Self::new_record(
            &mut inner,
            &peer.id,
            request.file_name,
            size,
            Direction::Upload,
            None,
            false,
        )
    }
    pub fn offer_download(&self, peer: &str, source: impl AsRef<Path>) -> Result<Transfer> {
        self.offer_download_checked(peer, source.as_ref(), None, None)
    }
    /// Bind a desktop offer to the session in which its files were selected.
    pub fn offer_download_in_session(
        &self,
        peer: &str,
        source: impl AsRef<Path>,
        session_id: &str,
    ) -> Result<Transfer> {
        self.offer_download_checked(peer, source.as_ref(), Some(session_id), None)
    }
    pub fn offer_retry_download(
        &self,
        peer: &str,
        source: &Path,
        session_id: &str,
        expected: &SourceStamp,
    ) -> Result<Transfer> {
        self.offer_download_checked(peer, source, Some(session_id), Some(expected))
    }
    fn offer_download_checked(
        &self,
        peer: &str,
        source: &Path,
        session_id: Option<&str>,
        expected: Option<&SourceStamp>,
    ) -> Result<Transfer> {
        let (name, snapshot) = Self::inspect_source(source)?;
        if expected.is_some_and(|stamp| !stamp.matches(&snapshot)) {
            return Err(Fault::new(409, "source_changed"));
        }
        let mut inner = self.lock();
        if session_id.is_some_and(|id| {
            inner
                .session
                .as_ref()
                .is_none_or(|session| session.info.session_id != id)
        }) {
            return Err(Fault::new(409, "session_closed"));
        }
        self.expire_locked(&mut inner);
        Self::new_record(
            &mut inner,
            peer,
            name,
            snapshot.len,
            Direction::Download,
            Some(snapshot),
            false,
        )
    }
    fn inspect_source(source: &Path) -> Result<(String, Source)> {
        let source = fs::canonicalize(source).map_err(|e| io_fault(&e))?;
        let name = source
            .file_name()
            .and_then(|s| s.to_str())
            .ok_or(Fault::new(400, "invalid_name"))?
            .to_owned();
        if !valid_name(&name) {
            return Err(Fault::new(400, "invalid_name"));
        }
        let metadata = fs::metadata(&source).map_err(|e| io_fault(&e))?;
        if !metadata.is_file() {
            return Err(Fault::new(400, "invalid_request"));
        }
        if metadata.len() > MAX_SIZE {
            return Err(Fault::new(413, "limit_exceeded"));
        }
        Ok((
            name,
            Source {
                path: source,
                len: metadata.len(),
                modified: metadata.modified().map_err(|e| io_fault(&e))?,
            },
        ))
    }
    pub fn accept_upload(&self, id: &str, destination: impl AsRef<Path>) -> Result<Transfer> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        let index = inner
            .records
            .iter()
            .position(|r| r.transfer.transfer_id == id)
            .ok_or(Fault::new(404, "not_found"))?;
        let record = &inner.records[index];
        if record.transfer.direction != Direction::Upload
            || record.transfer.state != TransferState::AwaitingAcceptance
        {
            return Err(Fault::new(409, "invalid_state"));
        }
        self.accept_upload_locked(&mut inner, index, destination.as_ref())
    }
    fn accept_upload_locked(
        &self,
        inner: &mut Inner,
        index: usize,
        destination: &Path,
    ) -> Result<Transfer> {
        let record = &inner.records[index];
        let directory = fs::canonicalize(destination).map_err(|e| io_fault(&e))?;
        if !directory.is_dir() {
            return Err(Fault::new(500, "io_error"));
        }
        if fs2::available_space(&directory).map_err(|e| io_fault(&e))?
            < record.transfer.size_bytes.parse::<u64>().unwrap()
        {
            return Err(Fault::new(507, "insufficient_space"));
        }
        let temp = store::partial(&directory).map_err(|e| io_fault(&e))?;
        inner.registry.partials.push(temp.path().into());
        if let Err(error) = self.save_registry(&inner.registry) {
            inner.registry.partials.pop();
            return Err(error);
        }
        let record = &mut inner.records[index];
        record.temp = Some(temp);
        record.directory = Some(directory);
        record.transfer.state = TransferState::Ready;
        record.changed = Instant::now();
        Ok(record.transfer.clone())
    }
    fn accept_download(&self, peer: &AuthenticatedPeer, id: &str) -> Result<Transfer> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        let index = Self::record_index(&inner, peer, id)?;
        let record = &mut inner.records[index];
        if record.transfer.direction != Direction::Download
            || !matches!(
                record.transfer.state,
                TransferState::AwaitingAcceptance | TransferState::Ready
            )
        {
            return Err(Fault::new(409, "invalid_state"));
        }
        if record.transfer.state != TransferState::Ready {
            record.transfer.state = TransferState::Ready;
            record.changed = Instant::now();
        }
        Ok(record.transfer.clone())
    }
    pub fn cancel(&self, id: &str) -> Result<Transfer> {
        let mut inner = self.lock();
        let record = inner
            .records
            .iter()
            .find(|r| r.transfer.transfer_id == id)
            .ok_or(Fault::new(404, "not_found"))?;
        if matches!(
            record.transfer.state,
            TransferState::Completed | TransferState::Failed
        ) {
            return Err(Fault::new(409, "invalid_state"));
        }
        self.terminate(&mut inner, id, TransferState::Cancelled, "cancelled");
        Ok(inner
            .records
            .iter()
            .find(|r| r.transfer.transfer_id == id)
            .unwrap()
            .transfer
            .clone())
    }
    fn cancel_remote(&self, peer: &AuthenticatedPeer, id: &str) -> Result<Transfer> {
        // Keep authorization and transition in one lock to cover concurrent revocation.
        let mut inner = self.lock();
        let index = Self::record_index(&inner, peer, id)?;
        if matches!(
            inner.records[index].transfer.state,
            TransferState::Completed | TransferState::Failed
        ) {
            return Err(Fault::new(409, "invalid_state"));
        }
        self.terminate(&mut inner, id, TransferState::Cancelled, "cancelled");
        Ok(inner.records[index].transfer.clone())
    }
    fn status(&self, peer: &AuthenticatedPeer, id: &str) -> Result<Transfer> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        let index = Self::record_index(&inner, peer, id)?;
        Ok(inner.records[index].transfer.clone())
    }
    fn offers(&self, peer: &AuthenticatedPeer) -> Result<Vec<Transfer>> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::ensure_authenticated(&inner, peer)?;
        Ok(inner
            .records
            .iter()
            .filter(|r| {
                r.peer_id == peer.id
                    && r.transfer.direction == Direction::Download
                    && r.transfer.state == TransferState::AwaitingAcceptance
            })
            .map(|r| r.transfer.clone())
            .collect())
    }
    fn claim_stream(
        &self,
        peer: &AuthenticatedPeer,
        id: &str,
        direction: Direction,
    ) -> Result<(File, CancellationToken, u64)> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        let index = Self::record_index(&inner, peer, id)?;
        let record = &mut inner.records[index];
        if record.transfer.direction != direction || record.transfer.state != TransferState::Ready {
            return Err(Fault::new(409, "invalid_state"));
        }
        let file_result = match direction {
            Direction::Upload => record
                .temp
                .as_ref()
                .ok_or(Fault::new(500, "io_error"))?
                .reopen()
                .map_err(|e| io_fault(&e)),
            Direction::Download => {
                let source = record.source.as_ref().unwrap();
                match File::open(&source.path) {
                    Ok(file) => {
                        let metadata = file.metadata().map_err(|e| io_fault(&e))?;
                        if metadata.len() != source.len
                            || metadata.modified().ok() != Some(source.modified)
                        {
                            Err(Fault::new(409, "source_changed"))
                        } else {
                            Ok(file)
                        }
                    }
                    Err(error) => Err(io_fault(&error)),
                }
            }
        };
        let file = match file_result {
            Ok(file) => file,
            Err(error) => {
                self.terminate(&mut inner, id, TransferState::Failed, error.code);
                return Err(error);
            }
        };
        let record = &mut inner.records[index];
        if direction == Direction::Download && record.transfer.size_bytes == "0" {
            record.final_bytes_released = true;
        }
        record.transfer.state = TransferState::Streaming;
        record.changed = Instant::now();
        record.progressed = Instant::now();
        Ok((
            file,
            record.cancel.clone(),
            record.transfer.size_bytes.parse().unwrap(),
        ))
    }
    fn mark_final_download_bytes(&self, peer: &AuthenticatedPeer, id: &str) -> Result<()> {
        let mut inner = self.lock();
        let index = Self::record_index(&inner, peer, id)?;
        let record = &mut inner.records[index];
        if record.transfer.state != TransferState::Streaming {
            return Err(Fault::new(409, "invalid_state"));
        }
        record.final_bytes_released = true;
        Ok(())
    }
    fn progress(&self, peer: &AuthenticatedPeer, id: &str, bytes: u64) -> Result<()> {
        let mut inner = self.lock();
        let index = Self::record_index(&inner, peer, id)?;
        inner.peer_activity.insert(peer.id.clone(), Instant::now());
        let record = &mut inner.records[index];
        if record.cancel.is_cancelled() || record.transfer.state != TransferState::Streaming {
            return Err(Fault::new(409, "invalid_state"));
        }
        let size = record.transfer.size_bytes.parse::<u64>().unwrap();
        let before = record.transfer.bytes_transferred.parse::<u64>().unwrap();
        if bytes < before || bytes > size {
            return Err(Fault::new(400, "size_mismatch"));
        }
        record.transfer.bytes_transferred = bytes.to_string();
        record.progressed = Instant::now();
        Ok(())
    }
    fn finish_stream(&self, peer: &AuthenticatedPeer, id: &str, hash: String) -> Result<Transfer> {
        let mut inner = self.lock();
        let index = Self::record_index(&inner, peer, id)?;
        inner.peer_activity.insert(peer.id.clone(), Instant::now());
        let record = &mut inner.records[index];
        if record.cancel.is_cancelled() || record.transfer.state != TransferState::Streaming {
            return Err(Fault::new(409, "invalid_state"));
        }
        if record.transfer.bytes_transferred != record.transfer.size_bytes {
            return Err(Fault::new(400, "size_mismatch"));
        }
        if let Some(source) = &record.source {
            let unchanged = fs::metadata(&source.path)
                .is_ok_and(|m| m.len() == source.len && m.modified().ok() == Some(source.modified));
            if !unchanged {
                self.terminate(&mut inner, id, TransferState::Failed, "source_changed");
                return Err(Fault::new(409, "source_changed"));
            }
        }
        record.transfer.state = TransferState::AwaitingVerification;
        record.transfer.sha256 = Some(hash);
        record.changed = Instant::now();
        Ok(record.transfer.clone())
    }
    fn fail_stream(&self, id: &str, code: &'static str) {
        self.terminate(&mut self.lock(), id, TransferState::Failed, code);
    }
    fn complete(&self, peer: &AuthenticatedPeer, id: &str, hash: &str) -> Result<Transfer> {
        if !valid_hash(hash) {
            return Err(Fault::new(400, "invalid_request"));
        }
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        let index = Self::record_index(&inner, peer, id)?;
        let record = &mut inner.records[index];
        if !matches!(
            record.transfer.state,
            TransferState::AwaitingVerification | TransferState::Completed
        ) {
            return Err(Fault::new(409, "invalid_state"));
        }
        if record.transfer.sha256.as_deref() != Some(hash) {
            self.terminate(&mut inner, id, TransferState::Failed, "integrity_mismatch");
            return Err(Fault::new(409, "integrity_mismatch"));
        }
        if record.transfer.state == TransferState::Completed {
            return Ok(record.transfer.clone());
        }
        if record.transfer.direction == Direction::Upload {
            let temp = record.temp.take().ok_or(Fault::new(500, "io_error"))?;
            let old_path = temp.path().to_path_buf();
            match store::publish(
                temp,
                record.directory.as_ref().unwrap(),
                &record.transfer.file_name,
            ) {
                Ok(path) => record.published = Some(path),
                Err(error) => {
                    self.terminate(&mut inner, id, TransferState::Failed, io_fault(&error).code);
                    inner.registry.partials.retain(|p| p != &old_path);
                    let _ = self.save_registry(&inner.registry);
                    return Err(io_fault(&error));
                }
            }
            inner.registry.partials.retain(|p| p != &old_path);
            let _ = self.save_registry(&inner.registry);
        }
        let record = &mut inner.records[index];
        record.transfer.state = TransferState::Completed;
        record.changed = Instant::now();
        let transfer = record.transfer.clone();
        Self::sync_batches_locked(&mut inner);
        Ok(transfer)
    }
    pub fn snapshot(&self) -> Snapshot {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::sync_batches_locked(&mut inner);
        Snapshot {
            upload_batch: inner.upload_batch.as_ref().map(|b| b.local.clone()),
            download_batch: inner.download_batch.as_ref().map(|b| b.local.clone()),
            session: inner.session.as_ref().map(|s| s.info.clone()),
            receive_enabled: inner.receive,
            peers: inner
                .registry
                .peers
                .iter()
                .map(|p| Peer {
                    supports_inbox: inner.inbox_peers.contains(&p.id),
                    id: p.id.clone(),
                    name: p.name.clone(),
                })
                .collect(),
            transfers: inner
                .records
                .iter()
                .map(|r| LocalTransfer {
                    transfer: r.transfer.clone(),
                    peer_id: r.peer_id.clone(),
                    destination: r.published.clone(),
                    delivery_uncertain: r.final_bytes_released
                        && r.transfer.state != TransferState::Completed,
                })
                .collect(),
        }
    }
}

#[cfg(test)]
mod session_tests {
    use super::*;
    fn registered(core: &Engine, id: &str, credential: &str) {
        core.lock().registry.peers.push(store::PeerRecord {
            id: id.into(),
            name: "Prueba".into(),
            credential_hash: digest(credential.as_bytes()),
        });
    }
    #[test]
    fn ephemeral_open_clears_stale_peers_preserves_identity_and_cleans_only_owned_partials() {
        let data = tempfile::tempdir().unwrap();
        let files = tempfile::tempdir().unwrap();
        let legacy = Engine::open(data.path(), "Prueba").unwrap();
        let cert = legacy.certificate_der().to_vec();
        let id = new_id();
        registered(&legacy, &id, &random_secret().unwrap());
        let partial = store::partial(files.path())
            .unwrap()
            .into_temp_path()
            .keep()
            .unwrap();
        let completed = files.path().join("verified.txt");
        fs::write(&completed, b"verified").unwrap();
        legacy
            .lock()
            .registry
            .partials
            .extend([partial.clone(), completed.clone()]);
        legacy.save_registry(&legacy.lock().registry).unwrap();
        drop(legacy);
        let desktop = Engine::open_ephemeral(data.path(), "Prueba").unwrap();
        assert_eq!(desktop.certificate_der(), cert);
        assert!(desktop.snapshot().peers.is_empty());
        assert!(!partial.exists());
        assert_eq!(fs::read(completed).unwrap(), b"verified");
        let journaled = files.path().join(".file-transfer-owned.part");
        fs::write(&journaled, b"partial").unwrap();
        registered(&desktop, &new_id(), &random_secret().unwrap());
        desktop.lock().registry.partials.push(journaled.clone());
        desktop.save_registry(&desktop.lock().registry).unwrap();
        let disk = store::registry(data.path()).unwrap();
        assert!(disk.peers.is_empty());
        assert_eq!(disk.partials, vec![journaled]);
    }
    #[test]
    fn exact_credential_binding_rejects_an_authenticated_request_after_id_is_repaired() {
        let data = tempfile::tempdir().unwrap();
        let core = Engine::open_ephemeral(data.path(), "Prueba").unwrap();
        let id = new_id();
        let old_credential = random_secret().unwrap();
        registered(&core, &id, &old_credential);
        let request = core.authenticate(&id, &old_credential).unwrap();
        core.revoke_peer(&id).unwrap();
        let replacement = random_secret().unwrap();
        registered(&core, &id, &replacement);
        assert_eq!(core.disconnect(&request).unwrap_err().code, "unauthorized");
        assert!(core.authenticate(&id, &replacement).is_ok());
        assert_eq!(
            core.authenticate(&id, &old_credential).err().unwrap().code,
            "unauthorized"
        );
    }
    #[test]
    fn ephemeral_pair_and_disconnect_do_not_depend_on_registry_writes() {
        let data = tempfile::tempdir().unwrap();
        let core = Engine::open_ephemeral(data.path(), "Prueba").unwrap();
        fs::remove_file(data.path().join("peers.json")).unwrap();
        fs::create_dir(data.path().join("peers.json")).unwrap();
        let token = random_secret().unwrap();
        core.lock().window = Some(PairWindow {
            token_hash: hash_secret(&token),
            opened: Instant::now(),
            attempts: 0,
        });
        let id = new_id();
        let response = core
            .pair(
                PairRequest {
                    device_id: id.clone(),
                    device_name: "Prueba".into(),
                    pairing_token: token,
                },
                false,
            )
            .unwrap();
        let authenticated = core.authenticate(&id, &response.credential).unwrap();
        core.disconnect(&authenticated).unwrap();
        assert!(core.snapshot().peers.is_empty());
    }
    #[test]
    fn only_authenticated_activity_renews_inbox_lease() {
        let data = tempfile::tempdir().unwrap();
        let core = Engine::open_ephemeral(data.path(), "Prueba").unwrap();
        let id = new_id();
        let credential = random_secret().unwrap();
        registered(&core, &id, &credential);
        let request = core.authenticate(&id, &credential).unwrap();
        core.inbox(&request).unwrap();
        core.lock()
            .peer_activity
            .insert(id.clone(), Instant::now() - Duration::from_secs(44));
        assert!(core.authenticate(&id, &random_secret().unwrap()).is_err());
        assert!(core.lock().peer_activity[&id].elapsed() >= Duration::from_secs(44));
        core.authenticate(&id, &credential).unwrap();
        assert!(core.lock().peer_activity[&id].elapsed() < Duration::from_secs(1));
        core.lock()
            .peer_activity
            .insert(id, Instant::now() - Duration::from_secs(46));
        assert!(core.snapshot().peers.is_empty());
        assert!(core.authenticate(&request.id, &credential).is_err());
    }
}
