use super::*;
use file_transfer_core::{BatchState, BatchStatus};

impl Fixture {
    async fn batch_request(&self, peer: &Paired, files: Value) -> reqwest::Response {
        self.auth(
            peer,
            self.client.post(format!("{}/v1/upload-batches", self.base)),
        )
        .json(&json!({"files":files}))
        .send()
        .await
        .unwrap()
    }
    async fn batch(&self, peer: &Paired, files: Value) -> BatchStatus {
        self.core.set_receive_enabled(true).unwrap();
        let response = self.batch_request(peer, files).await;
        assert_eq!(response.status(), StatusCode::CREATED);
        response.json().await.unwrap()
    }
    async fn batch_action(
        &self,
        peer: &Paired,
        batch: &BatchStatus,
        action: &str,
        body: Value,
    ) -> reqwest::Response {
        self.auth(
            peer,
            self.client.post(format!(
                "{}/v1/upload-batches/{}/{action}",
                self.base, batch.batch_id
            )),
        )
        .json(&body)
        .send()
        .await
        .unwrap()
    }
    async fn batch_state(&self, peer: &Paired, batch: &BatchStatus) -> BatchStatus {
        self.auth(
            peer,
            self.client.get(format!(
                "{}/v1/upload-batches/{}",
                self.base, batch.batch_id
            )),
        )
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap()
    }
    async fn batch_start(&self, peer: &Paired, batch: &BatchStatus, index: usize) -> Transfer {
        let response = self
            .batch_action(peer, batch, "start", json!({"index":index}))
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        let transfer: Transfer = response.json().await.unwrap();
        assert_eq!(transfer.state, TransferState::Ready);
        transfer
    }
}
fn files(count: usize) -> Value {
    Value::Array(
        (0..count)
            .map(|_| json!({"file_name":"same.txt","size_bytes":"3"}))
            .collect(),
    )
}
fn local(f: &Fixture) -> Value {
    serde_json::to_value(f.core.snapshot()).unwrap()["upload_batch"].clone()
}

#[tokio::test]
async fn one_local_approval_binds_manifest_peer_order_and_verified_files() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    let other = f.pair().await;
    assert!(local(&f).is_null());
    let batch = f.batch(&peer, files(2)).await;
    assert_eq!(batch.state, BatchState::AwaitingAcceptance);
    assert_eq!(local(&f)["files"][0]["state"], "pending");
    assert!(local(&f)["files"][0]["transfer_id"].is_null());
    assert_eq!(
        f.batch_action(&peer, &batch, "start", json!({"index":0}))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    for action in ["start", "cancel"] {
        assert_eq!(
            f.batch_action(&other, &batch, action, json!({"index":0}))
                .await
                .status(),
            StatusCode::NOT_FOUND
        );
    }
    let response = f
        .client
        .get(format!("{}/v1/upload-batches/{}", f.base, batch.batch_id))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(
        f.batch_request(&peer, files(2)).await.status(),
        StatusCode::CONFLICT
    );
    let source = f.destination.path().join("source.txt");
    fs::write(&source, b"abc").unwrap();
    assert_eq!(
        f.core.offer_download(&peer.id, &source).unwrap_err().code,
        "busy"
    );
    assert_eq!(
        f.auth(&peer, f.client.post(format!("{}/v1/transfers", f.base)))
            .json(&json!({"file_name":"extra.txt","size_bytes":"3"}))
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(
        f.core
            .accept_upload_batch(&batch.batch_id, f.destination.path())
            .unwrap()
            .state,
        BatchState::Accepted
    );
    assert_eq!(
        f.core
            .accept_upload_batch(&batch.batch_id, f.destination.path())
            .unwrap_err()
            .code,
        "invalid_state"
    );
    f.core.set_receive_enabled(false).unwrap();
    assert_eq!(
        f.batch_request(&peer, files(2)).await.status(),
        StatusCode::CONFLICT
    );
    for index in [1, 2, usize::MAX] {
        assert_eq!(
            f.batch_action(&peer, &batch, "start", json!({"index":index}))
                .await
                .status(),
            StatusCode::CONFLICT
        );
    }
    let first = f.batch_start(&peer, &batch, 0).await;
    assert_eq!(first.file_name, "same.txt");
    for index in [0, 1] {
        assert_eq!(
            f.batch_action(&peer, &batch, "start", json!({"index":index}))
                .await
                .status(),
            StatusCode::CONFLICT
        );
    }
    assert_eq!(
        f.upload(&peer, &first, b"abc".to_vec()).await.status(),
        StatusCode::OK
    );
    assert_eq!(
        f.batch_action(&peer, &batch, "start", json!({"index":1}))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    assert!(!f.destination.path().join("same.txt").exists());
    assert_eq!(
        f.complete(&peer, &first.transfer_id, &digest(b"abc"))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        f.complete(&peer, &first.transfer_id, &digest(b"wrong replay"))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(
        f.batch_state(&peer, &batch).await.state,
        BatchState::Accepted
    );
    let second = f.batch_start(&peer, &batch, 1).await;
    assert_eq!(
        f.upload(&peer, &second, b"def".to_vec()).await.status(),
        StatusCode::OK
    );
    assert_eq!(
        f.complete(&peer, &second.transfer_id, &digest(b"def"))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        f.batch_state(&peer, &batch).await.state,
        BatchState::Completed
    );
    assert_eq!(
        fs::read(f.destination.path().join("same.txt")).unwrap(),
        b"abc"
    );
    assert_eq!(
        fs::read(f.destination.path().join("same (1).txt")).unwrap(),
        b"def"
    );
    assert_eq!(local(&f)["files"][0]["state"], "completed");
    assert_eq!(local(&f)["files"][1]["state"], "completed");
    assert_eq!(
        f.batch_action(&peer, &batch, "start", json!({"index":1}))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    f.core.stop_session();
    assert!(local(&f).is_null());
}

#[tokio::test]
async fn validation_rejection_cancel_and_integrity_stop_remaining_entries() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    f.core.set_receive_enabled(true).unwrap();
    for invalid in [
        files(0),
        files(1),
        files(65),
        json!([{"file_name":"../bad","size_bytes":"3"},{"file_name":"ok","size_bytes":"3"}]),
        json!([{"file_name":"ok","size_bytes":"03"},{"file_name":"ok","size_bytes":"3"}]),
    ] {
        assert_eq!(
            f.batch_request(&peer, invalid).await.status(),
            StatusCode::BAD_REQUEST
        );
    }
    for body in [
        r#"{"files":[],"files":[]}"#,
        r#"{"files":[{"file_name":"a","file_name":"b","size_bytes":"0"},{"file_name":"b","size_bytes":"0"}]}"#,
        r#"{"files":null}"#,
        r#"{"files":[],"extra":true}"#,
    ] {
        assert_eq!(
            f.auth(
                &peer,
                f.client.post(format!("{}/v1/upload-batches", f.base))
            )
            .header("Content-Type", "application/json")
            .body(body)
            .send()
            .await
            .unwrap()
            .status(),
            StatusCode::BAD_REQUEST
        );
    }
    assert_eq!(
        f.auth(
            &peer,
            f.client.post(format!("{}/v1/upload-batches", f.base))
        )
        .header("Content-Type", "application/json")
        .body(" ".repeat(16385))
        .send()
        .await
        .unwrap()
        .status(),
        StatusCode::PAYLOAD_TOO_LARGE
    );
    let rejected = f.batch(&peer, files(2)).await;
    assert_eq!(
        f.core
            .cancel_upload_batch(&rejected.batch_id)
            .unwrap()
            .state,
        BatchState::Cancelled
    );
    assert_eq!(
        f.batch_action(&peer, &rejected, "cancel", json!({}))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(local(&f)["files"][0]["state"], "not_sent");
    assert_eq!(
        f.batch_action(&peer, &rejected, "start", json!({"index":0}))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    let batch = f.batch(&peer, files(3)).await;
    f.core
        .accept_upload_batch(&batch.batch_id, f.destination.path())
        .unwrap();
    for invalid in [
        json!({"index":-1}),
        json!({"index":0.0}),
        json!({"index":"0"}),
        json!({"index":null}),
        json!({"index":0,"file_name":"changed"}),
    ] {
        assert_eq!(
            f.batch_action(&peer, &batch, "start", invalid)
                .await
                .status(),
            StatusCode::BAD_REQUEST
        );
    }
    let first = f.batch_start(&peer, &batch, 0).await;
    f.upload(&peer, &first, b"abc".to_vec()).await;
    assert_eq!(
        f.complete(&peer, &first.transfer_id, &digest(b"abc"))
            .await
            .status(),
        StatusCode::OK
    );
    let second = f.batch_start(&peer, &batch, 1).await;
    f.upload(&peer, &second, b"def".to_vec()).await;
    assert_eq!(
        f.complete(&peer, &second.transfer_id, &digest(b"bad"))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(f.batch_state(&peer, &batch).await.state, BatchState::Failed);
    assert_eq!(local(&f)["files"][0]["state"], "completed");
    assert_eq!(local(&f)["files"][1]["state"], "failed");
    assert_eq!(local(&f)["files"][2]["state"], "not_sent");
    assert_eq!(
        fs::read(f.destination.path().join("same.txt")).unwrap(),
        b"abc"
    );
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 1);
    assert_eq!(
        f.batch_action(&peer, &batch, "start", json!({"index":2}))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    let cancel = f.batch(&peer, files(2)).await;
    f.core
        .accept_upload_batch(&cancel.batch_id, f.destination.path())
        .unwrap();
    let ready = f.batch_start(&peer, &cancel, 0).await;
    assert_eq!(
        f.batch_action(&peer, &cancel, "cancel", json!({}))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        f.state(&peer, &ready.transfer_id).await.state,
        TransferState::Cancelled
    );
    assert_eq!(local(&f)["files"][1]["state"], "not_sent");
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 1);
}

#[tokio::test]
async fn sixty_four_files_keep_manifest_results_after_record_eviction() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    let batch = f.batch(&peer, files(64)).await;
    f.core
        .accept_upload_batch(&batch.batch_id, f.destination.path())
        .unwrap();
    let mut first_id = String::new();
    for index in 0..64 {
        let transfer = f.batch_start(&peer, &batch, index).await;
        if index == 0 {
            first_id = transfer.transfer_id.clone();
        }
        assert_eq!(
            f.upload(&peer, &transfer, b"abc".to_vec()).await.status(),
            StatusCode::OK
        );
        assert_eq!(
            f.complete(&peer, &transfer.transfer_id, &digest(b"abc"))
                .await
                .status(),
            StatusCode::OK
        );
    }
    assert_eq!(f.core.snapshot().transfers.len(), 32);
    assert_eq!(
        f.auth(
            &peer,
            f.client.get(format!("{}/v1/transfers/{first_id}", f.base))
        )
        .send()
        .await
        .unwrap()
        .status(),
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        f.batch_state(&peer, &batch).await.state,
        BatchState::Completed
    );
    let local = local(&f);
    assert_eq!(local["files"].as_array().unwrap().len(), 64);
    assert!(
        local["files"]
            .as_array()
            .unwrap()
            .iter()
            .all(|row| row["state"] == "completed")
    );
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 64);
}

#[tokio::test]
async fn waiting_between_file_idle_and_revocation_end_batch_authorization() {
    let f = Fixture::timed(Timeouts {
        waiting: Duration::from_millis(80),
        idle: Duration::from_millis(80),
        ..Timeouts::default()
    })
    .await;
    let peer = f.pair().await;
    let waiting = f.batch(&peer, files(2)).await;
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(
        f.batch_state(&peer, &waiting).await.state,
        BatchState::Cancelled
    );
    let accepted = f.batch(&peer, files(2)).await;
    f.core
        .accept_upload_batch(&accepted.batch_id, f.destination.path())
        .unwrap();
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(
        f.batch_state(&peer, &accepted).await.state,
        BatchState::Failed
    );
    let streaming = f.batch(&peer, files(2)).await;
    f.core
        .accept_upload_batch(&streaming.batch_id, f.destination.path())
        .unwrap();
    let t = f.batch_start(&peer, &streaming, 0).await;
    let tls = raw_upload_start(&f, &peer, &t, b"a").await;
    assert_eq!(
        wait_for_failed(&f, &peer, &t.transfer_id).await.state,
        TransferState::Failed
    );
    assert_eq!(
        f.batch_state(&peer, &streaming).await.state,
        BatchState::Failed
    );
    assert_eq!(local(&f)["files"][1]["state"], "not_sent");
    drop(tls);
    let revoked = f.batch(&peer, files(2)).await;
    f.core
        .accept_upload_batch(&revoked.batch_id, f.destination.path())
        .unwrap();
    f.core.revoke_peer(&peer.id).unwrap();
    assert_eq!(local(&f)["state"], "cancelled");
    assert_eq!(
        f.batch_action(&peer, &revoked, "start", json!({"index":0}))
            .await
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 0);
}

#[tokio::test]
async fn batch_cancel_during_tls_stream_preserves_completed_and_stops_remaining() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    let batch = f.batch(&peer, files(3)).await;
    f.core
        .accept_upload_batch(&batch.batch_id, f.destination.path())
        .unwrap();
    let first = f.batch_start(&peer, &batch, 0).await;
    f.upload(&peer, &first, b"abc".to_vec()).await;
    assert_eq!(
        f.complete(&peer, &first.transfer_id, &digest(b"abc"))
            .await
            .status(),
        StatusCode::OK
    );
    let second = f.batch_start(&peer, &batch, 1).await;
    let tls = raw_upload_start(&f, &peer, &second, b"d").await;
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            if f.state(&peer, &second.transfer_id).await.bytes_transferred == "1" {
                break;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(
        f.batch_action(&peer, &batch, "cancel", json!({}))
            .await
            .status(),
        StatusCode::OK
    );
    drop(tls);
    assert_eq!(
        f.batch_state(&peer, &batch).await.state,
        BatchState::Cancelled
    );
    assert_eq!(local(&f)["files"][0]["state"], "completed");
    assert_eq!(local(&f)["files"][1]["state"], "cancelled");
    assert_eq!(local(&f)["files"][2]["state"], "not_sent");
    assert_eq!(
        fs::read(f.destination.path().join("same.txt")).unwrap(),
        b"abc"
    );
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 1);
    assert_eq!(
        f.batch_action(&peer, &batch, "start", json!({"index":2}))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    let next = f.batch(&peer, files(2)).await;
    f.core
        .accept_upload_batch(&next.batch_id, f.destination.path())
        .unwrap();
    let first = f.batch_start(&peer, &next, 0).await;
    f.core.cancel(&first.transfer_id).unwrap();
    assert_eq!(
        f.batch_state(&peer, &next).await.state,
        BatchState::Cancelled
    );
    assert_eq!(local(&f)["files"][1]["state"], "not_sent");
}

#[tokio::test]
async fn destination_failure_after_approval_fails_batch_without_preparing_rest() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    let batch = f.batch(&peer, files(2)).await;
    let chosen = f.destination.path().join("chosen");
    fs::create_dir(&chosen).unwrap();
    f.core
        .accept_upload_batch(&batch.batch_id, &chosen)
        .unwrap();
    fs::remove_dir(&chosen).unwrap();
    assert_eq!(
        f.batch_action(&peer, &batch, "start", json!({"index":0}))
            .await
            .status(),
        StatusCode::INTERNAL_SERVER_ERROR
    );
    assert_eq!(f.batch_state(&peer, &batch).await.state, BatchState::Failed);
    assert_eq!(local(&f)["files"][0]["state"], "failed");
    assert_eq!(local(&f)["files"][1]["state"], "not_sent");
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 0);
}
