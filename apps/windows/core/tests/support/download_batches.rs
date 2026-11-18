use super::*;
use file_transfer_core::{BatchState, BatchStatus};

impl Fixture {
    async fn inbox(&self, peer: &Paired) -> Value {
        let response = self
            .auth(peer, self.client.get(format!("{}/v1/inbox", self.base)))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        response.json().await.unwrap()
    }
    fn offer_batch(&self, peer: &Paired, count: usize) -> BatchStatus {
        let sources = (0..count)
            .map(|index| {
                let source = self.destination.path().join(format!("source-{index}.txt"));
                fs::write(&source, format!("file-{index}")).unwrap();
                source
            })
            .collect();
        let session = self.core.snapshot().session.unwrap().session_id;
        self.core
            .offer_download_batch(&peer.id, sources, &session)
            .unwrap()
    }
    async fn download_action(
        &self,
        peer: &Paired,
        batch: &BatchStatus,
        action: &str,
        body: Value,
    ) -> reqwest::Response {
        self.auth(
            peer,
            self.client.post(format!(
                "{}/v1/download-batches/{}/{action}",
                self.base, batch.batch_id
            )),
        )
        .json(&body)
        .send()
        .await
        .unwrap()
    }
    async fn download_state(&self, peer: &Paired, batch: &BatchStatus) -> BatchStatus {
        let response = self
            .auth(
                peer,
                self.client.get(format!(
                    "{}/v1/download-batches/{}",
                    self.base, batch.batch_id
                )),
            )
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        response.json().await.unwrap()
    }
    async fn download_start(&self, peer: &Paired, batch: &BatchStatus, index: usize) -> Transfer {
        let response = self
            .download_action(peer, batch, "start", json!({"index":index}))
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        let transfer: Transfer = response.json().await.unwrap();
        assert_eq!(transfer.state, TransferState::Ready);
        transfer
    }
    async fn download_verified(&self, peer: &Paired, transfer: &Transfer, expected: &[u8]) {
        let response = self
            .auth(
                peer,
                self.client.get(format!(
                    "{}/v1/transfers/{}/content",
                    self.base, transfer.transfer_id
                )),
            )
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.bytes().await.unwrap().as_ref(), expected);
        // Sender finalization can follow reception of the last TLS content frame.
        tokio::time::timeout(Duration::from_secs(2), async {
            while self.state(peer, &transfer.transfer_id).await.state == TransferState::Streaming {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert_eq!(
            self.complete(peer, &transfer.transfer_id, &digest(expected))
                .await
                .status(),
            StatusCode::OK
        );
    }
}
fn local(f: &Fixture) -> Value {
    serde_json::to_value(f.core.snapshot()).unwrap()["download_batch"].clone()
}

#[tokio::test]
async fn inbox_is_authenticated_scoped_and_disconnect_revokes_only_the_caller() {
    let f = Fixture::ephemeral().await;
    let peer = f.pair().await;
    let other = f.pair().await;
    let disk: Value =
        serde_json::from_slice(&fs::read(f._data.path().join("peers.json")).unwrap()).unwrap();
    assert_eq!(disk["peers"], json!([]));
    assert!(f.core.snapshot().peers.iter().all(|p| !p.supports_inbox));
    assert_eq!(
        f.client
            .get(format!("{}/v1/inbox", f.base))
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );
    let source = f.destination.path().join("single.txt");
    fs::write(&source, b"single").unwrap();
    let transfer = f.core.offer_download(&peer.id, &source).unwrap();
    let inbox = f.inbox(&peer).await;
    assert_eq!(inbox["transfer"]["transfer_id"], transfer.transfer_id);
    assert!(inbox["batch"].is_null());
    assert_eq!(f.inbox(&other).await, json!({"transfer":null,"batch":null}));
    assert!(f.core.snapshot().peers.iter().all(|p| p.supports_inbox));
    let response = f
        .auth(&peer, f.client.post(format!("{}/v1/disconnect", f.base)))
        .json(&json!({"extra":true}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let response = f
        .auth(&peer, f.client.post(format!("{}/v1/disconnect", f.base)))
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.json::<Value>().await.unwrap(),
        json!({"disconnected":true})
    );
    assert_eq!(
        f.core.snapshot().transfers[0].transfer.state,
        TransferState::Cancelled
    );
    assert_eq!(
        f.auth(&peer, f.client.get(format!("{}/v1/inbox", f.base)))
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(f.inbox(&other).await, json!({"transfer":null,"batch":null}));
    f.core.stop_session();
    assert!(f.core.snapshot().peers.is_empty());
}

#[tokio::test]
async fn download_manifest_approval_order_global_slot_and_sixty_four_results() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    let other = f.pair().await;
    let batch = f.offer_batch(&peer, 64);
    let inbox = f.inbox(&peer).await;
    assert!(inbox["transfer"].is_null());
    assert_eq!(inbox["batch"]["files"].as_array().unwrap().len(), 64);
    assert_eq!(
        inbox["batch"]["files"][0],
        json!({"file_name":"source-0.txt","size_bytes":"6"})
    );
    assert_eq!(f.inbox(&other).await, json!({"transfer":null,"batch":null}));
    for action in ["accept", "start", "cancel"] {
        assert_eq!(
            f.download_action(&other, &batch, action, json!({}))
                .await
                .status(),
            StatusCode::NOT_FOUND
        );
    }
    assert_eq!(
        f.download_action(&peer, &batch, "start", json!({"index":0}))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(
        f.core
            .offer_download(&peer.id, f.destination.path().join("source-0.txt"))
            .unwrap_err()
            .code,
        "busy"
    );
    f.core.set_receive_enabled(true).unwrap();
    for path in ["transfers", "upload-batches"] {
        let body = if path == "transfers" {
            json!({"file_name":"bypass","size_bytes":"0"})
        } else {
            json!({"files":[{"file_name":"a","size_bytes":"0"},{"file_name":"b","size_bytes":"0"}]})
        };
        assert_eq!(
            f.auth(&peer, f.client.post(format!("{}/v1/{path}", f.base)))
                .json(&body)
                .send()
                .await
                .unwrap()
                .status(),
            StatusCode::CONFLICT
        );
    }
    let session = f.core.snapshot().session.unwrap().session_id;
    assert_eq!(
        f.core
            .offer_download_batch(
                &peer.id,
                vec![f.destination.path().join("source-0.txt"); 2],
                &session
            )
            .unwrap_err()
            .code,
        "busy"
    );
    assert_eq!(
        f.download_action(&peer, &batch, "accept", json!({}))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        f.download_action(&peer, &batch, "accept", json!({}))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(f.inbox(&peer).await, json!({"transfer":null,"batch":null}));
    for index in [1, 64, usize::MAX] {
        assert_eq!(
            f.download_action(&peer, &batch, "start", json!({"index":index}))
                .await
                .status(),
            StatusCode::CONFLICT
        );
    }
    for invalid in [
        json!({"index":-1}),
        json!({"index":"0"}),
        json!({"index":0,"extra":true}),
    ] {
        assert_eq!(
            f.download_action(&peer, &batch, "start", invalid)
                .await
                .status(),
            StatusCode::BAD_REQUEST
        );
    }
    for index in 0..64 {
        let transfer = f.download_start(&peer, &batch, index).await;
        assert_eq!(
            f.download_action(&peer, &batch, "start", json!({"index":index}))
                .await
                .status(),
            StatusCode::CONFLICT
        );
        f.download_verified(&peer, &transfer, format!("file-{index}").as_bytes())
            .await;
    }
    assert_eq!(
        f.download_state(&peer, &batch).await.state,
        BatchState::Completed
    );
    assert_eq!(f.core.snapshot().transfers.len(), 32);
    assert!(
        local(&f)["files"]
            .as_array()
            .unwrap()
            .iter()
            .all(|file| file["state"] == "completed")
    );
    assert_eq!(
        f.core
            .cancel_download_batch(&batch.batch_id)
            .unwrap_err()
            .code,
        "invalid_state"
    );
}

#[tokio::test]
async fn immutable_sources_integrity_cancel_disconnect_and_expiry_stop_remainder() {
    let f = Fixture::timed(Timeouts {
        waiting: Duration::from_millis(150),
        ..Timeouts::default()
    })
    .await;
    let peer = f.pair().await;
    let batch = f.offer_batch(&peer, 3);
    f.download_action(&peer, &batch, "accept", json!({})).await;
    let first = f.download_start(&peer, &batch, 0).await;
    f.download_verified(&peer, &first, b"file-0").await;
    fs::write(f.destination.path().join("source-1.txt"), b"changed length").unwrap();
    let second = f.download_start(&peer, &batch, 1).await;
    assert_eq!(second.size_bytes, "6");
    let response = f
        .auth(
            &peer,
            f.client.get(format!(
                "{}/v1/transfers/{}/content",
                f.base, second.transfer_id
            )),
        )
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CONFLICT);
    assert_eq!(
        response.json::<Value>().await.unwrap()["error"]["code"],
        "source_changed"
    );
    assert_eq!(
        f.download_state(&peer, &batch).await.state,
        BatchState::Failed
    );
    assert_eq!(local(&f)["files"][0]["state"], "completed");
    assert_eq!(local(&f)["files"][1]["state"], "failed");
    assert_eq!(local(&f)["files"][2]["state"], "not_sent");
    let batch = f.offer_batch(&peer, 2);
    f.download_action(&peer, &batch, "accept", json!({})).await;
    let first = f.download_start(&peer, &batch, 0).await;
    assert_eq!(
        f.core.cancel_download_batch(&batch.batch_id).unwrap().state,
        BatchState::Cancelled
    );
    assert_eq!(
        f.state(&peer, &first.transfer_id).await.state,
        TransferState::Cancelled
    );
    assert_eq!(
        f.download_action(&peer, &batch, "cancel", json!({}))
            .await
            .status(),
        StatusCode::OK
    );
    let batch = f.offer_batch(&peer, 2);
    tokio::time::sleep(Duration::from_millis(170)).await;
    assert_eq!(
        f.download_state(&peer, &batch).await.state,
        BatchState::Cancelled
    );
    let batch = f.offer_batch(&peer, 2);
    f.download_action(&peer, &batch, "accept", json!({})).await;
    tokio::time::sleep(Duration::from_millis(170)).await;
    assert_eq!(
        f.download_state(&peer, &batch).await.state,
        BatchState::Failed
    );
    let batch = f.offer_batch(&peer, 2);
    f.auth(&peer, f.client.post(format!("{}/v1/disconnect", f.base)))
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(local(&f)["state"], "cancelled");
    assert_eq!(local(&f)["batch_id"], batch.batch_id);
}

#[tokio::test]
async fn creation_validates_count_preview_bound_and_session_before_reserving() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    let source = f.destination.path().join("file.txt");
    fs::write(&source, b"data").unwrap();
    let session = f.core.snapshot().session.unwrap().session_id;
    for count in [0, 1, 65] {
        assert_eq!(
            f.core
                .offer_download_batch(&peer.id, vec![source.clone(); count], &session)
                .unwrap_err()
                .code,
            "invalid_request"
        );
    }
    assert_eq!(
        f.core
            .offer_download_batch(&peer.id, vec![source.clone(); 2], &new_id())
            .unwrap_err()
            .code,
        "session_closed"
    );
    let long = f.destination.path().join("x".repeat(240));
    fs::write(&long, b"data").unwrap();
    assert_eq!(
        f.core
            .offer_download_batch(&peer.id, vec![long; 64], &session)
            .unwrap_err()
            .code,
        "invalid_name"
    );
    assert!(local(&f).is_null());
    let maximal = f.destination.path().join("x".repeat(180));
    fs::File::create(&maximal)
        .unwrap()
        .set_len(MAX_SIZE)
        .unwrap();
    let maximum_batch = f
        .core
        .offer_download_batch(&peer.id, vec![maximal; 64], &session)
        .unwrap();
    assert!(serde_json::to_vec(&f.inbox(&peer).await).unwrap().len() <= 16 * 1024);
    f.core
        .cancel_download_batch(&maximum_batch.batch_id)
        .unwrap();
    let batch = f
        .core
        .offer_download_batch(&peer.id, vec![source; 2], &session)
        .unwrap();
    f.core.revoke_peer(&peer.id).unwrap();
    assert_eq!(local(&f)["batch_id"], batch.batch_id);
    assert_eq!(local(&f)["state"], "cancelled");
    f.core.stop_session();
    assert!(local(&f).is_null());
}

#[tokio::test]
async fn ephemeral_inbox_lease_expires_over_real_tls_while_legacy_peer_survives() {
    let f = Fixture::ephemeral().await;
    let peer = f.pair().await;
    let legacy = f.pair().await;
    f.inbox(&peer).await;
    let batch = f.offer_batch(&peer, 2);
    tokio::time::sleep(Duration::from_secs(46)).await;
    let snapshot = f.core.snapshot();
    assert_eq!(snapshot.peers.len(), 1);
    assert_eq!(snapshot.peers[0].id, legacy.id);
    assert_eq!(local(&f)["state"], "cancelled");
    assert_eq!(local(&f)["batch_id"], batch.batch_id);
    assert_eq!(
        f.auth(&peer, f.client.get(format!("{}/v1/inbox", f.base)))
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        f.inbox(&legacy).await,
        json!({"transfer":null,"batch":null})
    );
}

#[tokio::test]
async fn disconnect_during_outbound_tls_stream_preserves_completed_and_cancels_remaining() {
    let f = Fixture::ephemeral().await;
    let peer = f.pair().await;
    let first_path = f.destination.path().join("first.txt");
    let large = f.destination.path().join("large.bin");
    let last = f.destination.path().join("last.txt");
    fs::write(&first_path, b"verified").unwrap();
    fs::File::create(&large)
        .unwrap()
        .set_len(64 * 1024 * 1024)
        .unwrap();
    fs::write(&last, b"last").unwrap();
    let session = f.core.snapshot().session.unwrap().session_id;
    let batch = f
        .core
        .offer_download_batch(&peer.id, vec![first_path, large, last], &session)
        .unwrap();
    f.download_action(&peer, &batch, "accept", json!({})).await;
    let first = f.download_start(&peer, &batch, 0).await;
    f.download_verified(&peer, &first, b"verified").await;
    let second = f.download_start(&peer, &batch, 1).await;
    let stream = f
        .auth(
            &peer,
            f.client.get(format!(
                "{}/v1/transfers/{}/content",
                f.base, second.transfer_id
            )),
        )
        .send()
        .await
        .unwrap();
    assert_eq!(stream.status(), StatusCode::OK);
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            let state = f.state(&peer, &second.transfer_id).await;
            if state.state == TransferState::Streaming && state.bytes_transferred != "0" {
                break;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(
        f.auth(&peer, f.client.post(format!("{}/v1/disconnect", f.base)))
            .json(&json!({}))
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    assert!(stream.bytes().await.is_err());
    assert_eq!(local(&f)["state"], "cancelled");
    assert_eq!(local(&f)["files"][0]["state"], "completed");
    assert_eq!(local(&f)["files"][1]["state"], "cancelled");
    assert_eq!(local(&f)["files"][2]["state"], "not_sent");
}
