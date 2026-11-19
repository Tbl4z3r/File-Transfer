use super::*;
use file_transfer_core::{BatchState, BatchStatus, inspect_selection};

async fn pair_folder(f: &Fixture) -> Paired {
    let descriptor = f.core.open_pairing_window().unwrap();
    let id = new_id();
    let response = f.client.post(format!("{}/v1/pair", f.base))
        .header("X-FT-Inbox", "v1").header("X-FT-Folders", "v1")
        .json(&json!({"pairing_token":descriptor.pairing_token,"device_id":id,"device_name":"Folder phone"}))
        .send().await.unwrap();
    assert_eq!(response.status(), StatusCode::CREATED);
    let response: PairResponse = response.json().await.unwrap();
    Paired {
        id,
        credential: response.credential,
    }
}
async fn create(f: &Fixture, peer: &Paired, body: Value) -> reqwest::Response {
    f.auth(peer, f.client.post(format!("{}/v1/upload-batches", f.base)))
        .json(&body)
        .send()
        .await
        .unwrap()
}
async fn action(
    f: &Fixture,
    peer: &Paired,
    batch: &BatchStatus,
    direction: &str,
    action: &str,
    body: Value,
) -> reqwest::Response {
    f.auth(
        peer,
        f.client.post(format!(
            "{}/v1/{direction}-batches/{}/{action}",
            f.base, batch.batch_id
        )),
    )
    .json(&body)
    .send()
    .await
    .unwrap()
}
fn root(f: &Fixture) -> std::path::PathBuf {
    let root = f.destination.path().join("Estudios");
    fs::create_dir_all(root.join("Apuntes")).unwrap();
    fs::create_dir(root.join("Vacia")).unwrap();
    fs::write(root.join("Apuntes/clase.pdf"), b"abc").unwrap();
    root
}
#[tokio::test]
async fn negotiation_is_authenticated_and_folders_never_fall_back_for_legacy_peers() {
    let f = Fixture::new().await;
    let old = f.pair().await;
    let new = pair_folder(&f).await;
    let endpoint = format!("{}/v1/folder-support", f.base);
    assert_eq!(
        f.client.get(&endpoint).send().await.unwrap().status(),
        StatusCode::UNAUTHORIZED
    );
    let response = f.auth(&old, f.client.get(endpoint)).send().await.unwrap();
    assert_eq!(
        response.json::<Value>().await.unwrap(),
        json!({"folders":true})
    );
    assert!(
        !f.core
            .list_peers()
            .iter()
            .find(|peer| peer.id == old.id)
            .unwrap()
            .supports_folders
    );
    assert!(
        f.core
            .list_peers()
            .iter()
            .find(|peer| peer.id == new.id)
            .unwrap()
            .supports_folders
    );
    let root = root(&f);
    let session = f.core.snapshot().session.unwrap().session_id;
    assert_eq!(
        f.core
            .offer_folder_batch(&old.id, vec![root.clone()], &session)
            .unwrap_err()
            .code,
        "folders_unsupported"
    );
    assert!(f.core.snapshot().download_batch.is_none());
    let batch = f
        .core
        .offer_folder_batch(&new.id, vec![root], &session)
        .unwrap();
    let inbox: Value = f
        .auth(&new, f.client.get(format!("{}/v1/inbox", f.base)))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(
        inbox["batch"]["directories"],
        json!(["Estudios", "Estudios/Apuntes", "Estudios/Vacia"])
    );
    assert_eq!(
        inbox["batch"]["files"][0]["relative_path"],
        "Estudios/Apuntes/clase.pdf"
    );
    assert_eq!(inbox["batch"]["batch_id"], batch.batch_id);
    f.core.revoke_peer(&new.id).unwrap();
    assert_eq!(
        f.core.snapshot().download_batch.unwrap().state,
        BatchState::Cancelled
    );
}
#[tokio::test]
async fn invalid_folder_manifests_reserve_nothing() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    f.core.set_receive_enabled(true).unwrap();
    let invalid = [
        json!({"directories":["../escape"],"files":[]}),
        json!({"directories":["/absolute"],"files":[]}),
        json!({"directories":["A\\B"],"files":[]}),
        json!({"directories":["A//B"],"files":[]}),
        json!({"directories":["A/child"],"files":[]}),
        json!({"directories":["A","a"],"files":[]}),
        json!({"directories":["CON"],"files":[]}),
        json!({"directories":[],"files":[]}),
        json!({"directories":null,"files":[]}),
        json!({"directories":["A"],"files":[{"file_name":"A","size_bytes":"0"}]}),
        json!({"directories":["A"],"files":[{"file_name":"x","size_bytes":"0","relative_path":"A/y"}]}),
        json!({"directories":["A"],"files":[{"file_name":"x","size_bytes":"0","relative_path":null}]}),
        json!({"directories":["A"],"files":[{"file_name":"x","size_bytes":"0","relative_path":"A/x"},{"file_name":"X","size_bytes":"0","relative_path":"a/X"}]}),
        json!({"directories":["A","A/x"],"files":[{"file_name":"x","size_bytes":"0","relative_path":"A/x"}]}),
        json!({"directories":["A"],"files":[{"file_name":"x","size_bytes":"0","relative_path":"B/x"}]}),
        json!({"directories":["A"],"files":[{"file_name":"x","size_bytes":"01","relative_path":"A/x"}]}),
    ];
    for body in invalid {
        let response = create(&f, &peer, body.clone()).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST, "{body}");
        assert!(f.core.snapshot().upload_batch.is_none());
    }
}
#[tokio::test]
async fn approved_tree_uses_a_new_root_preserves_empty_folders_and_verifies_the_file() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    f.core.set_receive_enabled(true).unwrap();
    fs::create_dir(f.destination.path().join("Estudios")).unwrap();
    fs::write(f.destination.path().join("Estudios/original"), b"keep").unwrap();
    let response = create(&f, &peer, json!({"directories":["Estudios","Estudios/Apuntes","Estudios/Vacia"],
        "files":[{"file_name":"clase.pdf","size_bytes":"3","relative_path":"Estudios/Apuntes/clase.pdf"}]})).await;
    assert_eq!(response.status(), StatusCode::CREATED);
    let batch: BatchStatus = response.json().await.unwrap();
    assert_eq!(
        action(&f, &peer, &batch, "upload", "start", json!({"index":0}))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    f.core
        .accept_upload_batch(&batch.batch_id, f.destination.path())
        .unwrap();
    assert!(f.destination.path().join("Estudios (1)/Vacia").is_dir());
    let transfer: Transfer = action(&f, &peer, &batch, "upload", "start", json!({"index":0}))
        .await
        .json()
        .await
        .unwrap();
    assert_eq!(transfer.file_name, "clase.pdf");
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
    assert_eq!(
        fs::read(f.destination.path().join("Estudios (1)/Apuntes/clase.pdf")).unwrap(),
        b"abc"
    );
    assert_eq!(
        fs::read(f.destination.path().join("Estudios/original")).unwrap(),
        b"keep"
    );
    assert_eq!(
        f.core.snapshot().upload_batch.unwrap().state,
        BatchState::Completed
    );
}
#[tokio::test]
async fn empty_folders_wait_for_consent_and_complete_only_when_accepted_in_each_direction() {
    let f = Fixture::new().await;
    let peer = pair_folder(&f).await;
    f.core.set_receive_enabled(true).unwrap();
    let batch: BatchStatus = create(
        &f,
        &peer,
        json!({"directories":["Empty","Empty/Nested"],"files":[]}),
    )
    .await
    .json()
    .await
    .unwrap();
    assert_eq!(
        f.core.snapshot().upload_batch.unwrap().state,
        BatchState::AwaitingAcceptance
    );
    assert!(!f.destination.path().join("Empty").exists());
    assert_eq!(
        f.core
            .accept_upload_batch(&batch.batch_id, f.destination.path())
            .unwrap()
            .state,
        BatchState::Completed
    );
    assert!(f.destination.path().join("Empty/Nested").is_dir());
    let session = f.core.snapshot().session.unwrap().session_id;
    let batch = f
        .core
        .offer_folder_batch(&peer.id, vec![f.destination.path().join("Empty")], &session)
        .unwrap();
    assert_eq!(
        f.core.snapshot().download_batch.unwrap().state,
        BatchState::AwaitingAcceptance
    );
    let response = action(&f, &peer, &batch, "download", "accept", json!({})).await;
    assert_eq!(
        response.json::<BatchStatus>().await.unwrap().state,
        BatchState::Completed
    );
}
#[test]
fn selection_is_bounded_and_round_trips_local_stamps_without_adding_new_files() {
    let dir = TempDir::new().unwrap();
    fs::create_dir(dir.path().join("Tree")).unwrap();
    fs::write(dir.path().join("Tree/file"), b"a").unwrap();
    let selection = inspect_selection(vec![dir.path().join("Tree")]).unwrap();
    let restored: file_transfer_core::FolderSelection =
        serde_json::from_value(serde_json::to_value(&selection).unwrap()).unwrap();
    fs::write(dir.path().join("Tree/unselected"), b"b").unwrap();
    restored.validate().unwrap();
    assert_eq!(restored.sources.len(), 1);
    fs::write(dir.path().join("Tree/file"), b"changed").unwrap();
    assert_eq!(restored.validate().unwrap_err().code, "source_changed");
    for index in 0..65 {
        fs::write(dir.path().join(format!("Tree/{index}")), b"x").unwrap();
    }
    assert_eq!(
        inspect_selection(vec![dir.path().join("Tree")])
            .err()
            .unwrap()
            .code,
        "limit_exceeded"
    );
}
#[cfg(unix)]
#[tokio::test]
async fn symlinks_are_rejected_in_selection_and_before_publication() {
    use std::os::unix::fs::symlink;
    let f = Fixture::new().await;
    let tree = root(&f);
    symlink(f._data.path(), tree.join("external")).unwrap();
    assert_eq!(
        inspect_selection(vec![tree.clone()]).err().unwrap().code,
        "invalid_request"
    );
    fs::remove_file(tree.join("external")).unwrap();
    let peer = f.pair().await;
    f.core.set_receive_enabled(true).unwrap();
    let batch: BatchStatus = create(
        &f,
        &peer,
        json!({"directories":["New","New/Sub"],
        "files":[{"file_name":"file","size_bytes":"3","relative_path":"New/Sub/file"}]}),
    )
    .await
    .json()
    .await
    .unwrap();
    f.core
        .accept_upload_batch(&batch.batch_id, f.destination.path())
        .unwrap();
    let transfer: Transfer = action(&f, &peer, &batch, "upload", "start", json!({"index":0}))
        .await
        .json()
        .await
        .unwrap();
    assert_eq!(
        f.upload(&peer, &transfer, b"abc".to_vec()).await.status(),
        StatusCode::OK
    );
    let sub = f.destination.path().join("New/Sub");
    fs::rename(&sub, f.destination.path().join("New/OriginalSub")).unwrap();
    symlink(f._data.path(), &sub).unwrap();
    assert_eq!(
        f.complete(&peer, &transfer.transfer_id, &digest(b"abc"))
            .await
            .status(),
        StatusCode::BAD_REQUEST
    );
    assert!(!f._data.path().join("file").exists());
    assert_eq!(
        f.core.snapshot().upload_batch.unwrap().state,
        BatchState::Failed
    );
}

#[tokio::test]
async fn folder_preview_limits_apply_to_the_full_encoded_inbox_before_reserving() {
    let f = Fixture::new().await;
    let peer = pair_folder(&f).await;
    let root = f.destination.path().join("R".repeat(180));
    fs::create_dir(&root).unwrap();
    for index in 0..64 {
        fs::write(root.join(format!("{}-{index:02}", "f".repeat(170))), b"a").unwrap();
    }
    let session = f.core.snapshot().session.unwrap().session_id;
    assert_eq!(
        f.core
            .offer_folder_batch(&peer.id, vec![root], &session)
            .unwrap_err()
            .code,
        "limit_exceeded"
    );
    assert!(f.core.snapshot().download_batch.is_none());
    assert!(f.core.snapshot().transfers.is_empty());
}

#[cfg(unix)]
#[tokio::test]
async fn replacing_an_offered_source_tree_with_a_symlink_fails_before_streaming() {
    use std::os::unix::fs::symlink;
    let f = Fixture::new().await;
    let peer = pair_folder(&f).await;
    let root = root(&f);
    let session = f.core.snapshot().session.unwrap().session_id;
    let batch = f
        .core
        .offer_folder_batch(&peer.id, vec![root.clone()], &session)
        .unwrap();
    assert_eq!(
        action(&f, &peer, &batch, "download", "accept", json!({}))
            .await
            .status(),
        StatusCode::OK
    );
    let transfer: Transfer = action(&f, &peer, &batch, "download", "start", json!({"index":0}))
        .await
        .json()
        .await
        .unwrap();
    fs::rename(&root, f.destination.path().join("OriginalEstudios")).unwrap();
    symlink(f._data.path(), &root).unwrap();
    let response = f
        .auth(
            &peer,
            f.client.get(format!(
                "{}/v1/transfers/{}/content",
                f.base, transfer.transfer_id
            )),
        )
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        f.core.snapshot().download_batch.unwrap().state,
        BatchState::Failed
    );
}
