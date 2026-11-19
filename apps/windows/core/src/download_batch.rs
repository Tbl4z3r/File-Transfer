//! Outbound manifests are session-only and share the single active-work reservation.
use crate::{
    upload_batch::{BatchFile, BatchFileState, SessionBatch, UnsentState},
    *,
};

#[derive(Serialize)]
pub(crate) struct InboxFile {
    file_name: String,
    size_bytes: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    relative_path: Option<String>,
}
#[derive(Serialize)]
pub(crate) struct InboxBatch {
    batch_id: String,
    files: Vec<InboxFile>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    directories: Vec<String>,
}
#[derive(Serialize)]
pub(crate) struct Inbox {
    transfer: Option<Transfer>,
    batch: Option<InboxBatch>,
}
impl SessionBatch {
    fn preview(&self) -> InboxBatch {
        InboxBatch {
            batch_id: self.local.batch_id.clone(),
            directories: self.local.directories.clone(),
            files: self
                .local
                .files
                .iter()
                .map(|file| InboxFile {
                    file_name: file.file_name.clone(),
                    size_bytes: file.size_bytes.clone(),
                    relative_path: file.relative_path.clone(),
                })
                .collect(),
        }
    }
}
impl Engine {
    pub(crate) fn inbox(&self, peer: &AuthenticatedPeer) -> Result<Inbox> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::ensure_authenticated(&inner, peer)?;
        inner.inbox_peers.insert(peer.id.clone());
        let batch = inner
            .download_batch
            .as_ref()
            .filter(|b| {
                b.local.peer_id == peer.id && b.local.state == BatchState::AwaitingAcceptance
            })
            .map(SessionBatch::preview);
        let transfer = inner
            .records
            .iter()
            .find(|r| {
                r.peer_id == peer.id
                    && r.transfer.direction == Direction::Download
                    && r.transfer.state == TransferState::AwaitingAcceptance
            })
            .map(|r| r.transfer.clone());
        Ok(Inbox { transfer, batch })
    }
    /// Bind all immutable metadata and source paths to the session selected by the desktop.
    pub fn offer_download_batch(
        &self,
        peer: &str,
        sources: Vec<PathBuf>,
        session_id: &str,
    ) -> Result<BatchStatus> {
        self.offer_download_batch_checked(peer, sources, session_id, None)
    }
    pub fn offer_retry_download_batch(
        &self,
        peer: &str,
        sources: Vec<PathBuf>,
        session_id: &str,
        expected: &[SourceStamp],
    ) -> Result<BatchStatus> {
        self.offer_download_batch_checked(peer, sources, session_id, Some(expected))
    }
    fn offer_download_batch_checked(
        &self,
        peer: &str,
        sources: Vec<PathBuf>,
        session_id: &str,
        expected: Option<&[SourceStamp]>,
    ) -> Result<BatchStatus> {
        self.offer_download_manifest(peer, sources, session_id, expected, vec![], vec![])
    }
    pub fn offer_folder_batch(
        &self,
        peer: &str,
        sources: Vec<PathBuf>,
        session_id: &str,
    ) -> Result<BatchStatus> {
        let selection = folders::inspect_selection(sources)?;
        self.offer_folder_selection(peer, selection, session_id)
    }
    pub fn offer_folder_selection(
        &self,
        peer: &str,
        selection: FolderSelection,
        session_id: &str,
    ) -> Result<BatchStatus> {
        selection.validate()?;
        if selection.directories.is_empty() {
            return Err(Fault::new(400, "invalid_request"));
        }
        let directories = selection
            .directories
            .iter()
            .map(|dir| dir.relative_path.clone())
            .collect();
        let relative = selection
            .sources
            .iter()
            .map(|source| source.relative_path.clone())
            .collect();
        let stamps: Vec<_> = selection
            .sources
            .iter()
            .map(|source| source.stamp.clone())
            .collect();
        let sources = selection
            .sources
            .into_iter()
            .map(|source| source.source)
            .collect();
        self.offer_download_manifest(
            peer,
            sources,
            session_id,
            Some(&stamps),
            directories,
            relative,
        )
    }
    fn offer_download_manifest(
        &self,
        peer: &str,
        sources: Vec<PathBuf>,
        session_id: &str,
        expected: Option<&[SourceStamp]>,
        directories: Vec<String>,
        relative: Vec<Option<String>>,
    ) -> Result<BatchStatus> {
        if sources.len() > 64
            || (directories.is_empty() && !(2..=64).contains(&sources.len()))
            || expected.is_some_and(|stamps| stamps.len() != sources.len())
            || (!directories.is_empty() && relative.len() != sources.len())
        {
            return Err(Fault::new(400, "invalid_request"));
        }
        let selected: Vec<_> = sources
            .iter()
            .map(|source| Self::inspect_source(source))
            .collect::<Result<_>>()?;
        if expected.is_some_and(|stamps| {
            stamps
                .iter()
                .zip(&selected)
                .any(|(stamp, (_, source))| !stamp.matches(source))
        }) {
            return Err(Fault::new(409, "source_changed"));
        }
        let batch = SessionBatch {
            local: LocalUploadBatch {
                batch_id: new_id(),
                peer_id: peer.into(),
                state: BatchState::AwaitingAcceptance,
                files: selected
                    .iter()
                    .enumerate()
                    .map(|(index, (name, source))| BatchFile {
                        file_name: name.clone(),
                        relative_path: relative.get(index).cloned().flatten(),
                        size_bytes: source.len.to_string(),
                        transfer_id: None,
                        state: BatchFileState::Unsent(UnsentState::Pending),
                    })
                    .collect(),
                directories,
            },
            directory: None,
            directory_paths: HashMap::new(),
            sources: selected.into_iter().map(|(_, source)| source).collect(),
            changed: Instant::now(),
        };
        let manifest: Vec<_> = batch
            .local
            .files
            .iter()
            .map(|file| folders::ManifestFile {
                file_name: file.file_name.clone(),
                size_bytes: file.size_bytes.clone(),
                relative_path: file.relative_path.clone(),
            })
            .collect();
        folders::validate_manifest(&manifest, &batch.local.directories)?;
        // Validate the full response including JSON escaping before publishing a reservation.
        let preview = Inbox {
            transfer: None,
            batch: Some(batch.preview()),
        };
        if serde_json::to_vec(&preview)
            .map_err(|_| Fault::new(500, "io_error"))?
            .len()
            > 16 * 1024
        {
            return Err(Fault::new(413, "limit_exceeded"));
        }
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        if inner
            .session
            .as_ref()
            .is_none_or(|session| session.info.session_id != session_id)
        {
            return Err(Fault::new(409, "session_closed"));
        }
        Self::ensure_peer(&inner, peer)?;
        if !batch.local.directories.is_empty() && !inner.folder_peers.contains(peer) {
            return Err(Fault::new(409, "folders_unsupported"));
        }
        if Self::batch_busy(&inner) || inner.records.iter().any(|r| !r.transfer.state.terminal()) {
            return Err(Fault::new(409, "busy"));
        }
        let status = batch.status();
        inner.download_batch = Some(batch);
        Ok(status)
    }
    fn download_batch_index(inner: &Inner, peer: &AuthenticatedPeer, id: &str) -> Result<()> {
        Self::ensure_authenticated(inner, peer)?;
        if inner
            .download_batch
            .as_ref()
            .is_some_and(|b| b.local.peer_id == peer.id && b.local.batch_id == id)
        {
            Ok(())
        } else {
            Err(Fault::new(404, "not_found"))
        }
    }
    pub(crate) fn download_batch_status(
        &self,
        peer: &AuthenticatedPeer,
        id: &str,
    ) -> Result<BatchStatus> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::download_batch_index(&inner, peer, id)?;
        Ok(inner.download_batch.as_ref().unwrap().status())
    }
    pub(crate) fn accept_download_batch(
        &self,
        peer: &AuthenticatedPeer,
        id: &str,
    ) -> Result<BatchStatus> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::download_batch_index(&inner, peer, id)?;
        let batch = inner.download_batch.as_mut().unwrap();
        if batch.local.state != BatchState::AwaitingAcceptance {
            return Err(Fault::new(409, "invalid_state"));
        }
        batch.local.state = if batch.local.files.is_empty() {
            BatchState::Completed
        } else {
            BatchState::Accepted
        };
        batch.changed = Instant::now();
        Ok(batch.status())
    }
    pub(crate) fn start_download_batch(
        &self,
        peer: &AuthenticatedPeer,
        id: &str,
        index: usize,
    ) -> Result<Transfer> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::download_batch_index(&inner, peer, id)?;
        let batch = inner.download_batch.as_ref().unwrap();
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
        let name = batch.local.files[index].file_name.clone();
        let source = batch.sources[index].clone();
        let folder_paths = !batch.local.directories.is_empty();
        let transfer = Self::new_record(
            &mut inner,
            &peer.id,
            name,
            source.len,
            Direction::Download,
            Some(source),
            true,
        )?;
        inner.download_batch.as_mut().unwrap().local.files[index].transfer_id =
            Some(transfer.transfer_id.clone());
        let record = inner.records.back_mut().unwrap();
        record.folder_paths = folder_paths;
        record.transfer.state = TransferState::Ready;
        let result = record.transfer.clone();
        Self::sync_batches_locked(&mut inner);
        Ok(result)
    }
    pub fn cancel_download_batch(&self, id: &str) -> Result<BatchStatus> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        self.cancel_download_batch_locked(&mut inner, id)
    }
    fn cancel_download_batch_locked(&self, inner: &mut Inner, id: &str) -> Result<BatchStatus> {
        let batch = inner
            .download_batch
            .as_ref()
            .filter(|b| b.local.batch_id == id)
            .ok_or(Fault::new(404, "not_found"))?;
        if matches!(
            batch.local.state,
            BatchState::Completed | BatchState::Failed
        ) {
            return Err(Fault::new(409, "invalid_state"));
        }
        self.stop_download_batch_locked(inner, BatchState::Cancelled, "cancelled");
        Ok(inner.download_batch.as_ref().unwrap().status())
    }
    pub(crate) fn cancel_download_batch_remote(
        &self,
        peer: &AuthenticatedPeer,
        id: &str,
    ) -> Result<BatchStatus> {
        let mut inner = self.lock();
        self.expire_locked(&mut inner);
        Self::download_batch_index(&inner, peer, id)?;
        self.cancel_download_batch_locked(&mut inner, id)
    }
}
