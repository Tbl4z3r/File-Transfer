pub mod model;
mod server;
mod store;

use base64::{
    Engine as _,
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
};
use fs2::FileExt;
use model::*;
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    collections::VecDeque,
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
    pub id: String,
    pub name: String,
}
#[derive(Clone, Serialize)]
pub struct LocalTransfer {
    #[serde(flatten)]
    pub transfer: Transfer,
    pub peer_id: String,
    pub destination: Option<PathBuf>,
}
#[derive(Serialize)]
pub struct Snapshot {
    pub session: Option<SessionInfo>,
    pub receive_enabled: bool,
    pub peers: Vec<Peer>,
    pub transfers: Vec<LocalTransfer>,
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
}
struct ActiveSession {
    info: SessionInfo,
    cancel: CancellationToken,
    closed: Arc<AtomicBool>,
}
struct Inner {
    registry: store::Registry,
    window: Option<PairWindow>,
    records: VecDeque<Record>,
    session: Option<ActiveSession>,
    closing: Option<Arc<AtomicBool>>,
    receive: bool,
}
pub struct Engine {
    directory: PathBuf,
    identity: store::Identity,
    cert: Vec<u8>,
    key: Vec<u8>,
    inner: Mutex<Inner>,
    timeouts: Timeouts,
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
        store::private_directory(directory.as_ref()).map_err(|e| io_fault(&e))?;
        let directory = fs::canonicalize(directory.as_ref()).map_err(|e| io_fault(&e))?;
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
            }),
            timeouts,
            _storage_lock: lock,
        }))
    }
    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().expect("engine mutex poisoned")
    }
    fn save_registry(&self, registry: &store::Registry) -> Result<()> {
        store::save(&self.directory.join("peers.json"), registry).map_err(|e| io_fault(&e))
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
        server::start(self.clone(), bind, port).await
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
        self.lock()
            .registry
            .peers
            .iter()
            .map(|p| Peer {
                id: p.id.clone(),
                name: p.name.clone(),
            })
            .collect()
    }
    pub fn revoke_peer(&self, id: &str) -> Result<()> {
        let mut inner = self.lock();
        let before = inner.registry.clone();
        inner.registry.peers.retain(|p| p.id != id);
        if let Err(error) = self.save_registry(&inner.registry) {
            inner.registry = before;
            return Err(error);
        }
        let ids: Vec<_> = inner
            .records
            .iter()
            .filter(|r| r.peer_id == id && !r.transfer.state.terminal())
            .map(|r| r.transfer.transfer_id.clone())
            .collect();
        for transfer in ids {
            self.terminate(&mut inner, &transfer, TransferState::Cancelled, "revoked");
        }
        Ok(())
    }
    fn pair(&self, request: PairRequest) -> Result<PairResponse> {
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
            id: request.device_id,
            name: request.device_name,
            credential_hash: digest(credential.as_bytes()),
        });
        if let Err(error) = self.save_registry(&inner.registry) {
            inner.registry.peers.pop();
            return Err(error);
        }
        inner.window = None;
        Ok(PairResponse {
            protocol_version: 1,
            server_device_id: self.identity.id.clone(),
            server_name: self.identity.name.clone(),
            credential,
        })
    }
    fn authenticate(&self, id: &str, credential: &str) -> Result<String> {
        if !valid_id(id) || !valid_secret(credential) {
            return Err(Fault::new(401, "unauthorized"));
        }
        let candidate = digest(credential.as_bytes());
        let inner = self.lock();
        if inner.registry.peers.iter().any(|p| {
            p.id == id && bool::from(p.credential_hash.as_bytes().ct_eq(candidate.as_bytes()))
        }) {
            Ok(id.into())
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
    fn record_index(inner: &Inner, peer: &str, id: &str) -> Result<usize> {
        Self::ensure_peer(inner, peer)?;
        inner
            .records
            .iter()
            .position(|r| r.peer_id == peer && r.transfer.transfer_id == id)
            .ok_or(Fault::new(404, "not_found"))
    }
    fn expire_locked(&self, inner: &mut Inner) {
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
    }
    fn new_record(
        inner: &mut Inner,
        peer: &str,
        name: String,
        size: u64,
        direction: Direction,
        source: Option<Source>,
    ) -> Result<Transfer> {
        if inner.session.is_none() {
            return Err(Fault::new(409, "session_closed"));
        }
        Self::ensure_peer(inner, peer)?;
        if inner.records.iter().any(|r| !r.transfer.state.terminal()) {
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
        });
        Ok(transfer)
    }
    fn create_upload(&self, peer: &str, request: CreateUpload) -> Result<Transfer> {
        if !valid_name(&request.file_name) {
            return Err(Fault::new(400, "invalid_name"));
        }
        let size = parse_size(&request.size_bytes).ok_or(Fault::new(400, "invalid_request"))?;
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        if !inner.receive {
            return Err(Fault::new(409, "receive_disabled"));
        }
        Self::new_record(
            &mut inner,
            peer,
            request.file_name,
            size,
            Direction::Upload,
            None,
        )
    }
    pub fn offer_download(&self, peer: &str, source: impl AsRef<Path>) -> Result<Transfer> {
        let source = fs::canonicalize(source.as_ref()).map_err(|e| io_fault(&e))?;
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
        let snapshot = Source {
            path: source,
            len: metadata.len(),
            modified: metadata.modified().map_err(|e| io_fault(&e))?,
        };
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::new_record(
            &mut inner,
            peer,
            name,
            snapshot.len,
            Direction::Download,
            Some(snapshot),
        )
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
        let directory = fs::canonicalize(destination.as_ref()).map_err(|e| io_fault(&e))?;
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
    fn accept_download(&self, peer: &str, id: &str) -> Result<Transfer> {
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
    fn cancel_remote(&self, peer: &str, id: &str) -> Result<Transfer> {
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
    fn status(&self, peer: &str, id: &str) -> Result<Transfer> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        let index = Self::record_index(&inner, peer, id)?;
        Ok(inner.records[index].transfer.clone())
    }
    fn offers(&self, peer: &str) -> Result<Vec<Transfer>> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::ensure_peer(&inner, peer)?;
        Ok(inner
            .records
            .iter()
            .filter(|r| {
                r.peer_id == peer
                    && r.transfer.direction == Direction::Download
                    && r.transfer.state == TransferState::AwaitingAcceptance
            })
            .map(|r| r.transfer.clone())
            .collect())
    }
    fn claim_stream(
        &self,
        peer: &str,
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
        record.transfer.state = TransferState::Streaming;
        record.changed = Instant::now();
        record.progressed = Instant::now();
        Ok((
            file,
            record.cancel.clone(),
            record.transfer.size_bytes.parse().unwrap(),
        ))
    }
    fn progress(&self, peer: &str, id: &str, bytes: u64) -> Result<()> {
        let mut inner = self.lock();
        let index = Self::record_index(&inner, peer, id)?;
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
    fn finish_stream(&self, peer: &str, id: &str, hash: String) -> Result<Transfer> {
        let mut inner = self.lock();
        let index = Self::record_index(&inner, peer, id)?;
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
    fn complete(&self, peer: &str, id: &str, hash: &str) -> Result<Transfer> {
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
        Ok(record.transfer.clone())
    }
    pub fn snapshot(&self) -> Snapshot {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Snapshot {
            session: inner.session.as_ref().map(|s| s.info.clone()),
            receive_enabled: inner.receive,
            peers: inner
                .registry
                .peers
                .iter()
                .map(|p| Peer {
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
                })
                .collect(),
        }
    }
}
