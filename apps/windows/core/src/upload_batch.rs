//! Session-only manifests. A batch owns the global slot until its last verified file.
use crate::*;
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BatchState {
    AwaitingAcceptance,
    Accepted,
    Completed,
    Cancelled,
    Failed,
}
impl BatchState {
    pub(crate) fn terminal(self) -> bool {
        matches!(self, Self::Completed | Self::Cancelled | Self::Failed)
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BatchStatus {
    pub batch_id: String,
    pub state: BatchState,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct CreateBatch {
    pub files: Vec<folders::ManifestFile>,
    #[serde(default, deserialize_with = "folders::nonempty_directories")]
    pub directories: Vec<String>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct StartBatch {
    pub index: usize,
}
#[derive(Clone, Serialize)]
#[serde(untagged)]
pub enum BatchFileState {
    Transfer(TransferState),
    Unsent(UnsentState),
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum UnsentState {
    Pending,
    NotSent,
}
#[derive(Clone, Serialize)]
pub struct BatchFile {
    pub file_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub relative_path: Option<String>,
    pub size_bytes: String,
    pub transfer_id: Option<String>,
    pub state: BatchFileState,
}
#[derive(Clone, Serialize)]
pub struct LocalUploadBatch {
    pub batch_id: String,
    pub peer_id: String,
    pub state: BatchState,
    pub files: Vec<BatchFile>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub directories: Vec<String>,
}
pub(crate) struct SessionBatch {
    pub local: LocalUploadBatch,
    pub(crate) directory: Option<PathBuf>,
    pub(crate) directory_paths: HashMap<String, PathBuf>,
    pub(crate) sources: Vec<Source>,
    pub(crate) changed: Instant,
}
impl SessionBatch {
    pub(crate) fn status(&self) -> BatchStatus {
        BatchStatus {
            batch_id: self.local.batch_id.clone(),
            state: self.local.state,
        }
    }
}
impl Engine {
    pub(crate) fn batch_busy(inner: &Inner) -> bool {
        [&inner.upload_batch, &inner.download_batch]
            .into_iter()
            .flatten()
            .any(|b| !b.local.state.terminal())
    }
    fn batch_index(inner: &Inner, peer: &AuthenticatedPeer, id: &str) -> Result<()> {
        Self::ensure_authenticated(inner, peer)?;
        if inner
            .upload_batch
            .as_ref()
            .is_some_and(|b| b.local.peer_id == peer.id && b.local.batch_id == id)
        {
            Ok(())
        } else {
            Err(Fault::new(404, "not_found"))
        }
    }
    pub(crate) fn sync_batches_locked(inner: &mut Inner) {
        for batch in [&mut inner.upload_batch, &mut inner.download_batch]
            .into_iter()
            .flatten()
        {
            Self::sync_batch(batch, &inner.records);
        }
    }
    fn sync_batch(batch: &mut SessionBatch, records: &VecDeque<Record>) {
        if batch.local.state.terminal() || batch.local.state == BatchState::AwaitingAcceptance {
            return;
        }
        for file in &mut batch.local.files {
            if let Some(record) = file
                .transfer_id
                .as_ref()
                .and_then(|id| records.iter().find(|r| &r.transfer.transfer_id == id))
            {
                if record.transfer.state == TransferState::Completed
                    && !matches!(
                        file.state,
                        BatchFileState::Transfer(TransferState::Completed)
                    )
                {
                    batch.changed = Instant::now();
                }
                file.state = BatchFileState::Transfer(record.transfer.state);
            }
        }
        let terminal = batch.local.files.iter().find_map(|f| match f.state {
            BatchFileState::Transfer(TransferState::Failed) => Some(BatchState::Failed),
            BatchFileState::Transfer(TransferState::Cancelled) => Some(BatchState::Cancelled),
            _ => None,
        });
        if let Some(state) = terminal {
            batch.local.state = state;
            for file in &mut batch.local.files {
                if file.transfer_id.is_none() {
                    file.state = BatchFileState::Unsent(UnsentState::NotSent);
                }
            }
        } else if batch
            .local
            .files
            .iter()
            .all(|f| matches!(f.state, BatchFileState::Transfer(TransferState::Completed)))
        {
            batch.local.state = BatchState::Completed;
        }
    }
    pub(crate) fn stop_upload_batch_locked(
        &self,
        inner: &mut Inner,
        state: BatchState,
        code: &str,
    ) {
        self.stop_batch_locked(inner, false, state, code);
    }
    pub(crate) fn stop_download_batch_locked(
        &self,
        inner: &mut Inner,
        state: BatchState,
        code: &str,
    ) {
        self.stop_batch_locked(inner, true, state, code);
    }
    fn stop_batch_locked(&self, inner: &mut Inner, download: bool, state: BatchState, code: &str) {
        let batch = if download {
            &mut inner.download_batch
        } else {
            &mut inner.upload_batch
        };
        let Some(batch) = batch.as_mut() else {
            return;
        };
        if batch.local.state.terminal() {
            return;
        }
        batch.local.state = state;
        let ids: Vec<_> = batch
            .local
            .files
            .iter_mut()
            .filter_map(|f| {
                if f.transfer_id.is_none() {
                    f.state = BatchFileState::Unsent(UnsentState::NotSent);
                }
                f.transfer_id.clone()
            })
            .collect();
        for id in ids {
            self.terminate(
                inner,
                &id,
                if state == BatchState::Failed {
                    TransferState::Failed
                } else {
                    TransferState::Cancelled
                },
                code,
            );
        }
        let batch = if download {
            &mut inner.download_batch
        } else {
            &mut inner.upload_batch
        };
        // Terminal batch results survive eviction of older transfer records.
        if let Some(batch) = batch.as_mut() {
            for file in &mut batch.local.files {
                if let Some(record) = file
                    .transfer_id
                    .as_ref()
                    .and_then(|id| inner.records.iter().find(|r| &r.transfer.transfer_id == id))
                {
                    file.state = BatchFileState::Transfer(record.transfer.state);
                }
            }
        }
    }
    pub(crate) fn expire_batches_locked(&self, inner: &mut Inner) {
        Self::sync_batches_locked(inner);
        for download in [false, true] {
            let batch = if download {
                &inner.download_batch
            } else {
                &inner.upload_batch
            };
            let Some(batch) = batch.as_ref() else {
                continue;
            };
            if batch.local.state.terminal() {
                continue;
            }
            let between_files = !inner.records.iter().any(|r| !r.transfer.state.terminal());
            if between_files && batch.changed.elapsed() >= self.timeouts.waiting {
                let state = if batch.local.state == BatchState::AwaitingAcceptance {
                    BatchState::Cancelled
                } else {
                    BatchState::Failed
                };
                self.stop_batch_locked(
                    inner,
                    download,
                    state,
                    if state == BatchState::Cancelled {
                        "cancelled"
                    } else {
                        "timeout"
                    },
                );
            }
        }
    }
    pub(crate) fn create_upload_batch(
        &self,
        peer: &AuthenticatedPeer,
        request: CreateBatch,
    ) -> Result<BatchStatus> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::ensure_authenticated(&inner, peer)?;
        if inner.session.is_none() {
            return Err(Fault::new(409, "session_closed"));
        }
        if !inner.receive {
            return Err(Fault::new(409, "receive_disabled"));
        }
        folders::validate_manifest(&request.files, &request.directories)?;
        if Self::batch_busy(&inner) || inner.records.iter().any(|r| !r.transfer.state.terminal()) {
            return Err(Fault::new(409, "busy"));
        }
        let batch = SessionBatch {
            local: LocalUploadBatch {
                batch_id: new_id(),
                peer_id: peer.id.clone(),
                state: BatchState::AwaitingAcceptance,
                files: request
                    .files
                    .into_iter()
                    .map(|f| BatchFile {
                        file_name: f.file_name,
                        relative_path: f.relative_path,
                        size_bytes: f.size_bytes,
                        transfer_id: None,
                        state: BatchFileState::Unsent(UnsentState::Pending),
                    })
                    .collect(),
                directories: request.directories,
            },
            directory: None,
            directory_paths: HashMap::new(),
            sources: Vec::new(),
            changed: Instant::now(),
        };
        let status = batch.status();
        inner.upload_batch = Some(batch);
        Ok(status)
    }
    pub fn accept_upload_batch(
        &self,
        id: &str,
        destination: impl AsRef<Path>,
    ) -> Result<BatchStatus> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        let batch = inner
            .upload_batch
            .as_ref()
            .filter(|b| b.local.batch_id == id)
            .ok_or(Fault::new(404, "not_found"))?;
        if batch.local.state != BatchState::AwaitingAcceptance {
            return Err(Fault::new(409, "invalid_state"));
        }
        let directory = fs::canonicalize(destination).map_err(|e| io_fault(&e))?;
        if !directory.is_dir() {
            return Err(Fault::new(500, "io_error"));
        }
        let size: u64 = batch
            .local
            .files
            .iter()
            .map(|f| parse_size(&f.size_bytes).unwrap())
            .sum();
        if fs2::available_space(&directory).map_err(|e| io_fault(&e))? < size {
            return Err(Fault::new(507, "insufficient_space"));
        }
        // Probe access at approval, then repeat preflight and journal each actual file.
        drop(store::partial(&directory).map_err(|e| io_fault(&e))?);
        let paths = folders::prepare_directories(&directory, &batch.local.directories)?;
        let batch = inner.upload_batch.as_mut().unwrap();
        batch.directory = Some(directory);
        batch.directory_paths = paths;
        batch.local.state = if batch.local.files.is_empty() {
            BatchState::Completed
        } else {
            BatchState::Accepted
        };
        batch.changed = Instant::now();
        Ok(batch.status())
    }
    pub(crate) fn upload_batch_status(
        &self,
        peer: &AuthenticatedPeer,
        id: &str,
    ) -> Result<BatchStatus> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::batch_index(&inner, peer, id)?;
        Ok(inner.upload_batch.as_ref().unwrap().status())
    }
    pub(crate) fn start_upload_batch(
        &self,
        peer: &AuthenticatedPeer,
        id: &str,
        index: usize,
    ) -> Result<Transfer> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::batch_index(&inner, peer, id)?;
        let batch = inner.upload_batch.as_ref().unwrap();
        let next =
            batch.local.files.iter().position(|f| {
                !matches!(f.state, BatchFileState::Transfer(TransferState::Completed))
            });
        if batch.local.state != BatchState::Accepted
            || next != Some(index)
            || batch.local.files[index].transfer_id.is_some()
        {
            return Err(Fault::new(409, "invalid_state"));
        }
        let file = &batch.local.files[index];
        let name = file.file_name.clone();
        let size = parse_size(&file.size_bytes).unwrap();
        let folder_paths = !batch.local.directories.is_empty();
        let destination = if let Some(relative) = &file.relative_path {
            let parent = relative.rsplit_once('/').unwrap().0;
            batch
                .directory_paths
                .get(parent)
                .cloned()
                .ok_or(Fault::new(500, "io_error"))?
        } else {
            batch.directory.clone().unwrap()
        };
        let transfer = Self::new_record(
            &mut inner,
            &peer.id,
            name,
            size,
            Direction::Upload,
            None,
            true,
        )?;
        inner.upload_batch.as_mut().unwrap().local.files[index].transfer_id =
            Some(transfer.transfer_id.clone());
        let record_index = inner.records.len() - 1;
        inner.records[record_index].folder_paths = folder_paths;
        match self.accept_upload_locked(&mut inner, record_index, &destination) {
            Ok(transfer) => {
                Self::sync_batches_locked(&mut inner);
                Ok(transfer)
            }
            Err(error) => {
                self.stop_upload_batch_locked(&mut inner, BatchState::Failed, error.code);
                Err(error)
            }
        }
    }
    pub fn cancel_upload_batch(&self, id: &str) -> Result<BatchStatus> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        self.cancel_upload_batch_locked(&mut inner, id)
    }
    fn cancel_upload_batch_locked(&self, inner: &mut Inner, id: &str) -> Result<BatchStatus> {
        let batch = inner
            .upload_batch
            .as_ref()
            .filter(|b| b.local.batch_id == id)
            .ok_or(Fault::new(404, "not_found"))?;
        if matches!(
            batch.local.state,
            BatchState::Completed | BatchState::Failed
        ) {
            return Err(Fault::new(409, "invalid_state"));
        }
        self.stop_upload_batch_locked(inner, BatchState::Cancelled, "cancelled");
        Ok(inner.upload_batch.as_ref().unwrap().status())
    }
    pub(crate) fn cancel_upload_batch_remote(
        &self,
        peer: &AuthenticatedPeer,
        id: &str,
    ) -> Result<BatchStatus> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::batch_index(&inner, peer, id)?;
        self.cancel_upload_batch_locked(&mut inner, id)
    }
}
