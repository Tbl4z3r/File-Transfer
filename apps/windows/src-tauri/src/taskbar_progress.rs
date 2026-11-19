//! Native Windows taskbar feedback; the existing snapshot remains the only source of truth.
use file_transfer_core::{BatchState, Snapshot, model::TransferState};

#[derive(Debug, PartialEq)]
pub enum Feedback {
    Clear,
    Waiting,
    Progress(u64),
}
fn percentage(done: u128, total: u128) -> u64 {
    if total == 0 {
        0
    } else {
        ((done * 100 / total).min(99)) as u64
    }
}
pub fn feedback(snapshot: &Snapshot) -> Feedback {
    if snapshot.session.is_none() {
        return Feedback::Clear;
    }
    let current = snapshot
        .transfers
        .iter()
        .find(|t| !t.transfer.state.terminal());
    let batch = [&snapshot.upload_batch, &snapshot.download_batch]
        .into_iter()
        .flatten()
        .find(|batch| {
            matches!(
                batch.state,
                BatchState::AwaitingAcceptance | BatchState::Accepted
            )
        });
    if let Some(batch) = batch {
        if batch.state == BatchState::AwaitingAcceptance {
            return Feedback::Waiting;
        }
        let total = batch
            .files
            .iter()
            .map(|file| file.size_bytes.parse::<u128>().unwrap_or(0))
            .sum();
        let done = batch
            .files
            .iter()
            .map(|file| {
                let size = file.size_bytes.parse::<u128>().unwrap_or(0);
                match file.transfer_id.as_ref() {
                    None => 0,
                    Some(id) => snapshot
                        .transfers
                        .iter()
                        .find(|t| &t.transfer.transfer_id == id)
                        .map(|t| {
                            t.transfer
                                .bytes_transferred
                                .parse::<u128>()
                                .unwrap_or(0)
                                .min(size)
                        })
                        // An active batch advances sequentially. Its evicted earlier records
                        // are already verified; pending files have no transfer_id.
                        .unwrap_or(size),
                }
            })
            .sum();
        return Feedback::Progress(percentage(done, total));
    }
    match current {
        None => Feedback::Clear,
        Some(t)
            if matches!(
                t.transfer.state,
                TransferState::AwaitingAcceptance | TransferState::Ready
            ) =>
        {
            Feedback::Waiting
        }
        Some(t) => Feedback::Progress(percentage(
            t.transfer.bytes_transferred.parse().unwrap_or(0),
            t.transfer.size_bytes.parse().unwrap_or(0),
        )),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use file_transfer_core::{
        LocalTransfer,
        model::{Direction, Transfer},
    };
    fn snapshot(state: TransferState, done: &str, total: &str) -> Snapshot {
        Snapshot {
            session: Some(file_transfer_core::SessionInfo {
                session_id: "test".into(),
                host: "192.168.1.1".into(),
                port: 5000,
                server_device_id: "test".into(),
                cert_sha256: "a".repeat(64),
            }),
            receive_enabled: false,
            peers: vec![],
            upload_batch: None,
            download_batch: None,
            transfers: vec![LocalTransfer {
                transfer: Transfer {
                    transfer_id: "test".into(),
                    direction: Direction::Download,
                    file_name: "test".into(),
                    size_bytes: total.into(),
                    bytes_transferred: done.into(),
                    state,
                    sha256: None,
                    error_code: None,
                },
                peer_id: "phone".into(),
                destination: None,
                delivery_uncertain: false,
            }],
        }
    }
    #[test]
    fn bytes_over_4_gib_and_verification_never_claim_completion() {
        assert_eq!(
            feedback(&snapshot(
                TransferState::Streaming,
                "5000000000",
                "10000000000"
            )),
            Feedback::Progress(50)
        );
        assert_eq!(
            feedback(&snapshot(TransferState::AwaitingVerification, "100", "100")),
            Feedback::Progress(99)
        );
        assert_eq!(
            feedback(&snapshot(TransferState::Streaming, "0", "0")),
            Feedback::Progress(0)
        );
    }
    #[test]
    fn waiting_terminal_and_closed_sessions_clear_correctly() {
        assert_eq!(
            feedback(&snapshot(TransferState::AwaitingAcceptance, "0", "20")),
            Feedback::Waiting
        );
        for state in [
            TransferState::Completed,
            TransferState::Failed,
            TransferState::Cancelled,
        ] {
            assert_eq!(feedback(&snapshot(state, "10", "20")), Feedback::Clear);
        }
        let mut closed = snapshot(TransferState::Streaming, "10", "20");
        closed.session = None;
        assert_eq!(feedback(&closed), Feedback::Clear);
    }
}
