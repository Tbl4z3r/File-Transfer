use file_transfer_core::{Engine, Timeouts, model::*};
use reqwest::{Client, RequestBuilder, StatusCode};
use rustls::pki_types::CertificateDer;
use serde_json::{Value, json};
use std::{
    fs,
    net::{Ipv4Addr, SocketAddr},
    sync::Arc,
    time::Duration,
};
use tempfile::TempDir;

struct Fixture {
    core: Arc<Engine>,
    _data: TempDir,
    destination: TempDir,
    client: Client,
    base: String,
    address: SocketAddr,
}
struct Paired {
    id: String,
    credential: String,
}
fn client(cert: &[u8], address: SocketAddr) -> Client {
    let mut roots = rustls::RootCertStore::empty();
    roots.add(CertificateDer::from(cert.to_vec())).unwrap();
    let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_protocol_versions(&[&rustls::version::TLS12])
    .unwrap()
    .with_root_certificates(roots)
    .with_no_client_auth();
    Client::builder()
        .use_preconfigured_tls(tls)
        .no_proxy()
        .https_only(true)
        .redirect(reqwest::redirect::Policy::none())
        .resolve(TLS_HOST, address)
        .timeout(Duration::from_secs(5))
        .build()
        .unwrap()
}
impl Fixture {
    async fn new() -> Self {
        Self::timed(Timeouts::default()).await
    }
    async fn timed(timeouts: Timeouts) -> Self {
        Self::configured(timeouts, false).await
    }
    async fn ephemeral() -> Self {
        Self::configured(Timeouts::default(), true).await
    }
    async fn configured(timeouts: Timeouts, ephemeral: bool) -> Self {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let data = TempDir::new().unwrap();
        let destination = TempDir::new().unwrap();
        let core = if ephemeral {
            Engine::open_ephemeral_with_timeouts(data.path(), "PC pruebas", timeouts)
        } else {
            Engine::open_with_timeouts(data.path(), "PC pruebas", timeouts)
        }
        .unwrap();
        let socket = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = socket.local_addr().unwrap();
        drop(socket);
        core.start_session(Ipv4Addr::LOCALHOST, address.port())
            .await
            .unwrap();
        let http = client(core.certificate_der(), address);
        Self {
            core,
            _data: data,
            destination,
            client: http,
            base: format!("https://{TLS_HOST}:{}", address.port()),
            address,
        }
    }
    async fn pair(&self) -> Paired {
        let descriptor = self.core.open_pairing_window().unwrap();
        let id = new_id();
        let response = self.client.post(format!("{}/v1/pair",self.base)).json(&json!({"pairing_token":descriptor.pairing_token,"device_id":id,"device_name":"Android pruebas"})).send().await.unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
        let value: PairResponse = response.json().await.unwrap();
        assert_eq!(value.server_device_id, self.core.server_device_id());
        Paired {
            id,
            credential: value.credential,
        }
    }
    fn auth(&self, peer: &Paired, builder: RequestBuilder) -> RequestBuilder {
        builder
            .header("X-Device-Id", &peer.id)
            .bearer_auth(&peer.credential)
    }
    async fn create(&self, peer: &Paired, name: &str, size: u64) -> Transfer {
        self.core.set_receive_enabled(true).unwrap();
        let response = self
            .auth(
                peer,
                self.client.post(format!("{}/v1/transfers", self.base)),
            )
            .json(&json!({"file_name":name,"size_bytes":size.to_string()}))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::CREATED);
        response.json().await.unwrap()
    }
    async fn upload(&self, peer: &Paired, transfer: &Transfer, data: Vec<u8>) -> reqwest::Response {
        self.auth(
            peer,
            self.client.put(format!(
                "{}/v1/transfers/{}/content",
                self.base, transfer.transfer_id
            )),
        )
        .header("Content-Type", "application/octet-stream")
        .header("Content-Length", data.len())
        .body(data)
        .send()
        .await
        .unwrap()
    }
    async fn complete(&self, peer: &Paired, id: &str, hash: &str) -> reqwest::Response {
        self.auth(
            peer,
            self.client
                .post(format!("{}/v1/transfers/{id}/complete", self.base)),
        )
        .json(&json!({"sha256":hash}))
        .send()
        .await
        .unwrap()
    }
    async fn state(&self, peer: &Paired, id: &str) -> Transfer {
        self.auth(
            peer,
            self.client.get(format!("{}/v1/transfers/{id}", self.base)),
        )
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        self.core.stop_session();
    }
}

#[tokio::test]
async fn real_tls_both_directions_consent_integrity_and_collision() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    fs::write(f.destination.path().join("prueba.txt"), b"original").unwrap();
    let data: Vec<u8> = (0..(BUFFER_SIZE * 5 + 3))
        .map(|i| (i % 251) as u8)
        .collect();
    let transfer = f.create(&peer, "prueba.txt", data.len() as u64).await;
    assert_eq!(
        f.upload(&peer, &transfer, data.clone()).await.status(),
        StatusCode::CONFLICT
    );
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 1);
    f.core
        .accept_upload(&transfer.transfer_id, f.destination.path())
        .unwrap();
    let response = f.upload(&peer, &transfer, data.clone()).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let state: Transfer = response.json().await.unwrap();
    assert_eq!(state.state, TransferState::AwaitingVerification);
    assert_eq!(state.sha256.as_deref(), Some(digest(&data).as_str()));
    assert!(!f.destination.path().join("prueba (1).txt").exists());
    assert_eq!(
        f.complete(&peer, &transfer.transfer_id, &digest(&data))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        f.complete(&peer, &transfer.transfer_id, &digest(&data))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        f.complete(&peer, &transfer.transfer_id, &digest(b"wrong"))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(
        fs::read(f.destination.path().join("prueba.txt")).unwrap(),
        b"original"
    );
    assert_eq!(
        fs::read(f.destination.path().join("prueba (1).txt")).unwrap(),
        data
    );
    assert_eq!(
        f.state(&peer, &transfer.transfer_id).await.state,
        TransferState::Completed
    );
    let source = f.destination.path().join("prueba (1).txt");
    let offer = f.core.offer_download(&peer.id, &source).unwrap();
    let content = format!("{}/v1/transfers/{}/content", f.base, offer.transfer_id);
    assert_eq!(
        f.auth(&peer, f.client.get(&content))
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::CONFLICT
    );
    let offers: Value = f
        .auth(&peer, f.client.get(format!("{}/v1/offers", f.base)))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(offers["transfers"][0]["transfer_id"], offer.transfer_id);
    let accept = f
        .auth(
            &peer,
            f.client.post(format!(
                "{}/v1/transfers/{}/accept",
                f.base, offer.transfer_id
            )),
        )
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(accept.status(), StatusCode::OK);
    let received = f
        .auth(&peer, f.client.get(content))
        .send()
        .await
        .unwrap()
        .bytes()
        .await
        .unwrap();
    assert_eq!(received.as_ref(), data);
    let mut status = f.state(&peer, &offer.transfer_id).await;
    for _ in 0..100 {
        if status.state != TransferState::Streaming {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
        status = f.state(&peer, &offer.transfer_id).await;
    }
    assert_eq!(status.state, TransferState::AwaitingVerification);
    assert_eq!(status.sha256, Some(digest(&data)));
    assert_eq!(
        f.complete(&peer, &offer.transfer_id, &digest(&data))
            .await
            .status(),
        StatusCode::OK
    );
}

#[tokio::test]
async fn peer_isolation_revocation_and_no_plain_http() {
    let f = Fixture::new().await;
    let a = f.pair().await;
    let b = f.pair().await;
    let request = f
        .auth(&a, f.client.post(format!("{}/v1/transfers", f.base)))
        .json(&json!({"file_name":"a.txt","size_bytes":"3"}))
        .send()
        .await
        .unwrap();
    assert_eq!(request.status(), StatusCode::CONFLICT);
    assert_eq!(
        request.json::<Value>().await.unwrap()["error"]["code"],
        "receive_disabled"
    );
    let transfer = f.create(&a, "a.txt", 3).await;
    for suffix in ["", "/content"] {
        assert_eq!(
            f.auth(
                &b,
                f.client.get(format!(
                    "{}/v1/transfers/{}{suffix}",
                    f.base, transfer.transfer_id
                ))
            )
            .send()
            .await
            .unwrap()
            .status(),
            StatusCode::NOT_FOUND
        );
    }
    f.core
        .accept_upload(&transfer.transfer_id, f.destination.path())
        .unwrap();
    f.core.revoke_peer(&a.id).unwrap();
    assert_eq!(
        f.upload(&a, &transfer, b"abc".to_vec()).await.status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        f.core.snapshot().transfers[0]
            .transfer
            .error_code
            .as_deref(),
        Some("revoked")
    );
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 0);
    let wrong = Fixture::new().await;
    let bad = client(wrong.core.certificate_der(), f.address);
    assert!(
        bad.post(format!("{}/v1/pair", f.base))
            .json(&json!({}))
            .send()
            .await
            .is_err()
    );
    assert!(
        f.client
            .get(format!("https://127.0.0.1:{}/v1/offers", f.address.port()))
            .send()
            .await
            .is_err()
    );
    let plain = Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(1))
        .build()
        .unwrap();
    assert!(
        plain
            .get(format!("http://{}/v1/offers", f.address))
            .send()
            .await
            .is_err()
    );
}

#[tokio::test]
async fn strict_requests_token_single_use_and_limits() {
    let f = Fixture::new().await;
    let descriptor = f.core.open_pairing_window().unwrap();
    let id = new_id();
    let request =
        json!({"pairing_token":descriptor.pairing_token,"device_id":id,"device_name":"Android"});
    assert_eq!(
        f.client
            .post(format!("{}/v1/pair", f.base))
            .json(&request)
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::CREATED
    );
    assert_eq!(
        f.client
            .post(format!("{}/v1/pair", f.base))
            .json(&request)
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );
    let peer = f.pair().await;
    f.core.set_receive_enabled(true).unwrap();
    for body in [
        r#"{"file_name":"a","size_bytes":"3","extra":1}"#,
        r#"{"file_name":"a","file_name":"b","size_bytes":"3"}"#,
        r#"{"file_name":"a","size_bytes":3}"#,
    ] {
        assert_eq!(
            f.auth(&peer, f.client.post(format!("{}/v1/transfers", f.base)))
                .header("Content-Type", "application/json")
                .body(body)
                .send()
                .await
                .unwrap()
                .status(),
            StatusCode::BAD_REQUEST
        );
    }
    let result = f
        .auth(&peer, f.client.post(format!("{}/v1/transfers", f.base)))
        .json(&json!({"file_name":"../escape","size_bytes":"3"}))
        .send()
        .await
        .unwrap();
    assert_eq!(
        result.json::<Value>().await.unwrap()["error"]["code"],
        "invalid_name"
    );
    let large = f.create(&peer, "large.bin", 4_294_967_297).await;
    assert_eq!(large.size_bytes, "4294967297");
    f.core.cancel(&large.transfer_id).unwrap();
    let response = f
        .auth(&peer, f.client.post(format!("{}/v2/transfers", f.base)))
        .json(&json!({}))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let response = f
        .auth(&peer, f.client.post(format!("{}/v1/transfers", f.base)))
        .header("Content-Type", "application/json")
        .body(" ".repeat(16385))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
}

#[tokio::test]
async fn mismatch_empty_cancel_and_timeout() {
    let timeouts = Timeouts {
        pairing: Duration::from_millis(80),
        waiting: Duration::from_millis(200),
        ..Timeouts::default()
    };
    let f = Fixture::timed(timeouts).await;
    let stale = f.core.open_pairing_window().unwrap();
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(f.client.post(format!("{}/v1/pair",f.base)).json(&json!({"pairing_token":stale.pairing_token,"device_id":new_id(),"device_name":"Android"})).send().await.unwrap().status(),StatusCode::UNAUTHORIZED);
    let peer = f.pair().await;
    let t = f.create(&peer, "bad.bin", 3).await;
    f.core
        .accept_upload(&t.transfer_id, f.destination.path())
        .unwrap();
    f.upload(&peer, &t, b"abc".to_vec()).await;
    assert_eq!(
        f.complete(&peer, &t.transfer_id, &digest(b"wrong"))
            .await
            .status(),
        StatusCode::CONFLICT
    );
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 0);
    let empty = f.create(&peer, "empty.txt", 0).await;
    f.core
        .accept_upload(&empty.transfer_id, f.destination.path())
        .unwrap();
    assert_eq!(
        f.upload(&peer, &empty, vec![]).await.status(),
        StatusCode::OK
    );
    assert_eq!(
        f.complete(&peer, &empty.transfer_id, &digest(b""))
            .await
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        fs::metadata(f.destination.path().join("empty.txt"))
            .unwrap()
            .len(),
        0
    );
    let t = f.create(&peer, "cancel.txt", 3).await;
    f.core
        .accept_upload(&t.transfer_id, f.destination.path())
        .unwrap();
    f.core.cancel(&t.transfer_id).unwrap();
    f.core.cancel(&t.transfer_id).unwrap();
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 1);
    let t = f.create(&peer, "timeout.txt", 3).await;
    tokio::time::sleep(Duration::from_millis(220)).await;
    assert_eq!(
        f.state(&peer, &t.transfer_id).await.state,
        TransferState::Cancelled
    );
}

async fn tls_stream(f: &Fixture) -> tokio_rustls::client::TlsStream<tokio::net::TcpStream> {
    let mut roots = rustls::RootCertStore::empty();
    roots
        .add(CertificateDer::from(f.core.certificate_der().to_vec()))
        .unwrap();
    let config = rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_protocol_versions(&[&rustls::version::TLS12])
    .unwrap()
    .with_root_certificates(roots)
    .with_no_client_auth();
    tokio_rustls::TlsConnector::from(Arc::new(config))
        .connect(
            rustls::pki_types::ServerName::try_from(TLS_HOST).unwrap(),
            tokio::net::TcpStream::connect(f.address).await.unwrap(),
        )
        .await
        .unwrap()
}
async fn raw_upload_start(
    f: &Fixture,
    peer: &Paired,
    transfer: &Transfer,
    prefix: &[u8],
) -> tokio_rustls::client::TlsStream<tokio::net::TcpStream> {
    use tokio::io::AsyncWriteExt;
    let mut tls = tls_stream(f).await;
    let head = format!(
        "PUT /v1/transfers/{}/content HTTP/1.1\r\nHost: file-transfer.local\r\nContent-Type: application/octet-stream\r\nContent-Length: {}\r\nX-Device-Id: {}\r\nAuthorization: Bearer {}\r\n\r\n",
        transfer.transfer_id, transfer.size_bytes, peer.id, peer.credential
    );
    tls.write_all(head.as_bytes()).await.unwrap();
    tls.write_all(prefix).await.unwrap();
    tls.flush().await.unwrap();
    tls
}
#[tokio::test]
async fn truncated_tls_body_is_failed_and_partial_deleted() {
    use tokio::io::AsyncWriteExt;
    let f = Fixture::new().await;
    let peer = f.pair().await;
    let t = f.create(&peer, "truncated.bin", 10).await;
    f.core
        .accept_upload(&t.transfer_id, f.destination.path())
        .unwrap();
    let mut tls = raw_upload_start(&f, &peer, &t, b"abc").await;
    tls.shutdown().await.unwrap();
    drop(tls);
    let mut state = f.state(&peer, &t.transfer_id).await;
    for _ in 0..30 {
        if state.state == TransferState::Failed {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
        state = f.state(&peer, &t.transfer_id).await;
    }
    assert_eq!(state.state, TransferState::Failed);
    assert_eq!(state.error_code.as_deref(), Some("size_mismatch"));
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 0);
}
#[tokio::test]
async fn revoke_during_stream_closes_writer_and_rejects_future_authentication() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    let t = f
        .create(&peer, "revoked.bin", (BUFFER_SIZE * 10) as u64)
        .await;
    f.core
        .accept_upload(&t.transfer_id, f.destination.path())
        .unwrap();
    let tls = raw_upload_start(&f, &peer, &t, &vec![7u8; BUFFER_SIZE]).await;
    for _ in 0..30 {
        if f.core.snapshot().transfers[0].transfer.bytes_transferred != "0" {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    f.core.revoke_peer(&peer.id).unwrap();
    drop(tls);
    let snapshot = f.core.snapshot();
    assert_eq!(
        snapshot.transfers[0].transfer.state,
        TransferState::Cancelled
    );
    assert_eq!(
        snapshot.transfers[0].transfer.error_code.as_deref(),
        Some("revoked")
    );
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 0);
    let response = f
        .auth(
            &peer,
            f.client
                .get(format!("{}/v1/transfers/{}", f.base, t.transfer_id)),
        )
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}
#[tokio::test]
async fn five_incorrect_pairing_attempts_close_window_and_duplicate_id_preserves_token() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    let d = f.core.open_pairing_window().unwrap();
    let url = format!("{}/v1/pair", f.base);
    let request =
        json!({"pairing_token":d.pairing_token,"device_id":peer.id,"device_name":"Android"});
    assert_eq!(
        f.client
            .post(&url)
            .json(&request)
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::CONFLICT
    );
    let request =
        json!({"pairing_token":d.pairing_token,"device_id":new_id(),"device_name":"Android"});
    assert_eq!(
        f.client
            .post(&url)
            .json(&request)
            .send()
            .await
            .unwrap()
            .status(),
        StatusCode::CREATED
    );
    let d = f.core.open_pairing_window().unwrap();
    for _ in 0..5 {
        assert_eq!(f.client.post(&url).json(&json!({"pairing_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","device_id":new_id(),"device_name":"Android"})).send().await.unwrap().status(),StatusCode::UNAUTHORIZED);
    }
    assert_eq!(f.client.post(&url).json(&json!({"pairing_token":d.pairing_token,"device_id":new_id(),"device_name":"Android"})).send().await.unwrap().status(),StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn source_changed_and_immediate_session_restart() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    let source = f.destination.path().join("source.bin");
    fs::write(&source, b"abc").unwrap();
    let offer = f.core.offer_download(&peer.id, &source).unwrap();
    f.auth(
        &peer,
        f.client.post(format!(
            "{}/v1/transfers/{}/accept",
            f.base, offer.transfer_id
        )),
    )
    .json(&json!({}))
    .send()
    .await
    .unwrap();
    fs::write(&source, b"changed-size").unwrap();
    let response = f
        .auth(
            &peer,
            f.client.get(format!(
                "{}/v1/transfers/{}/content",
                f.base, offer.transfer_id
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
        f.state(&peer, &offer.transfer_id).await.state,
        TransferState::Failed
    );
    let pin = f.core.cert_sha256();
    let server_id = f.core.server_device_id().to_owned();
    f.core.stop_session();
    f.core
        .start_session(Ipv4Addr::LOCALHOST, f.address.port())
        .await
        .unwrap();
    assert_eq!(f.core.cert_sha256(), pin);
    assert_eq!(f.core.server_device_id(), server_id);
    assert!(!f.core.snapshot().receive_enabled);
    assert!(f.core.snapshot().transfers.is_empty());
    assert_eq!(f.core.list_peers().len(), 1);
}

#[tokio::test]
async fn peer_hash_and_identity_persist_without_raw_credential() {
    let directory = TempDir::new().unwrap();
    let core = Engine::open(directory.path(), "PC persistente").unwrap();
    let socket = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = socket.local_addr().unwrap();
    drop(socket);
    core.start_session(Ipv4Addr::LOCALHOST, address.port())
        .await
        .unwrap();
    let http = client(core.certificate_der(), address);
    let descriptor = core.open_pairing_window().unwrap();
    let peer_id = new_id();
    let response:PairResponse=http.post(format!("https://{TLS_HOST}:{}/v1/pair",address.port())).json(&json!({"pairing_token":descriptor.pairing_token,"device_id":peer_id,"device_name":"Android"})).send().await.unwrap().json().await.unwrap();
    let registry = fs::read_to_string(directory.path().join("peers.json")).unwrap();
    assert!(!registry.contains(&response.credential));
    assert!(registry.contains(&digest(response.credential.as_bytes())));
    let pin = core.cert_sha256();
    let id = core.server_device_id().to_owned();
    core.stop_session();
    drop(http);
    for _ in 0..100 {
        if Arc::strong_count(&core) == 1 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert_eq!(Arc::strong_count(&core), 1);
    drop(core);
    let reopened = Engine::open(directory.path(), "Nombre posterior").unwrap();
    assert_eq!(reopened.cert_sha256(), pin);
    assert_eq!(reopened.server_device_id(), id);
    assert_eq!(reopened.list_peers()[0].id, peer_id);
}

#[tokio::test]
async fn ready_verification_and_idle_timeouts_delete_partials() {
    let timeouts = Timeouts {
        waiting: Duration::from_millis(70),
        verification: Duration::from_millis(70),
        idle: Duration::from_millis(70),
        ..Timeouts::default()
    };
    let f = Fixture::timed(timeouts).await;
    let peer = f.pair().await;
    let t = f.create(&peer, "ready.bin", 3).await;
    f.core
        .accept_upload(&t.transfer_id, f.destination.path())
        .unwrap();
    let status = wait_for_failed(&f, &peer, &t.transfer_id).await;
    assert_eq!(status.state, TransferState::Failed);
    assert_eq!(status.error_code.as_deref(), Some("timeout"));
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 0);
    let t = f.create(&peer, "verify.bin", 3).await;
    f.core
        .accept_upload(&t.transfer_id, f.destination.path())
        .unwrap();
    assert_eq!(
        f.upload(&peer, &t, b"abc".to_vec()).await.status(),
        StatusCode::OK
    );
    let status = wait_for_failed(&f, &peer, &t.transfer_id).await;
    assert_eq!(status.state, TransferState::Failed);
    assert_eq!(status.error_code.as_deref(), Some("timeout"));
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 0);
    let t = f.create(&peer, "idle.bin", 100).await;
    f.core
        .accept_upload(&t.transfer_id, f.destination.path())
        .unwrap();
    let tls = raw_upload_start(&f, &peer, &t, b"a").await;
    for _ in 0..100 {
        if f.core
            .snapshot()
            .transfers
            .last()
            .unwrap()
            .transfer
            .bytes_transferred
            == "1"
        {
            break;
        }
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert_eq!(
        f.core
            .snapshot()
            .transfers
            .last()
            .unwrap()
            .transfer
            .bytes_transferred,
        "1"
    );
    let status = wait_for_failed(&f, &peer, &t.transfer_id).await;
    assert_eq!(status.state, TransferState::Failed);
    assert_eq!(status.error_code.as_deref(), Some("timeout"));
    assert_eq!(fs::read_dir(f.destination.path()).unwrap().count(), 0);
    drop(tls);
}

async fn wait_for_failed(f: &Fixture, peer: &Paired, transfer_id: &str) -> Transfer {
    tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            let status = f.state(peer, transfer_id).await;
            if status.state == TransferState::Failed {
                return status;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .expect("transfer should fail after its configured timeout")
}

#[tokio::test]
async fn automatic_port_falls_back_and_advertises_the_bound_tls_listener() {
    let _ = rustls::crypto::ring::default_provider().install_default();
    let data = TempDir::new().unwrap();
    let core = Engine::open(data.path(), "PC puerto automático").unwrap();
    // The competing listener stays alive across bind; no probe-and-release race.
    let occupied = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 51337)).unwrap();
    assert!(
        core.start_session(Ipv4Addr::LOCALHOST, 51337)
            .await
            .is_err()
    );
    assert!(core.snapshot().session.is_none());
    let info = core.start_session_auto(Ipv4Addr::LOCALHOST).await.unwrap();
    assert_ne!(info.port, 51337);
    assert!(info.port >= 1024);
    assert_eq!(info.host, "127.0.0.1");
    let descriptor = core.open_pairing_window().unwrap();
    assert_eq!(descriptor.port, info.port);
    assert_eq!(descriptor.host, info.host);
    assert_eq!(core.snapshot().session.unwrap().port, info.port);
    assert!(
        serde_json::to_value(&descriptor)
            .unwrap()
            .get("session_id")
            .is_none()
    );
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, info.port));
    let response = client(core.certificate_der(), address)
        .get(format!("https://{TLS_HOST}:{}/v1/offers", info.port))
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    core.stop_session();
    drop(occupied);
    let preferred = core.start_session_auto(Ipv4Addr::LOCALHOST).await.unwrap();
    assert_eq!(preferred.port, 51337);
    assert_ne!(info.session_id, preferred.session_id);
    assert_eq!(info.cert_sha256, preferred.cert_sha256);
    core.stop_session();
}

#[tokio::test]
async fn automatic_port_keeps_ipv4_validation_and_explicit_port_validation() {
    let data = TempDir::new().unwrap();
    let core = Engine::open(data.path(), "PC validación").unwrap();
    for bind in [
        Ipv4Addr::UNSPECIFIED,
        Ipv4Addr::BROADCAST,
        Ipv4Addr::new(224, 0, 0, 1),
    ] {
        assert_eq!(
            core.start_session_auto(bind).await.err().unwrap().code,
            "invalid_request"
        );
    }
    for port in [0, 1023] {
        assert_eq!(
            core.start_session(Ipv4Addr::LOCALHOST, port)
                .await
                .err()
                .unwrap()
                .code,
            "invalid_request"
        );
    }
    assert!(core.snapshot().session.is_none());
}

#[tokio::test]
async fn desktop_offer_is_bound_to_the_selected_session_even_after_same_address_restart() {
    let f = Fixture::new().await;
    let peer = f.pair().await;
    let first = f.core.snapshot().session.unwrap();
    let source = f.destination.path().join("lote.txt");
    fs::write(&source, b"lote").unwrap();
    assert_eq!(
        f.core
            .offer_download_in_session(&peer.id, &source, "stale-session")
            .unwrap_err()
            .code,
        "session_closed"
    );
    let offered = f
        .core
        .offer_download_in_session(&peer.id, &source, &first.session_id)
        .unwrap();
    f.core.cancel(&offered.transfer_id).unwrap();
    f.core.stop_session();
    let second = f
        .core
        .start_session(Ipv4Addr::LOCALHOST, f.address.port())
        .await
        .unwrap();
    assert_ne!(first.session_id, second.session_id);
    assert_eq!(
        f.core
            .offer_download_in_session(&peer.id, &source, &first.session_id)
            .unwrap_err()
            .code,
        "session_closed"
    );
    assert!(f.core.snapshot().transfers.is_empty());
    f.core
        .offer_download_in_session(&peer.id, &source, &second.session_id)
        .unwrap();
    f.core.stop_session();
}

#[path = "support/upload_batches.rs"]
mod upload_batches;

#[path = "support/download_batches.rs"]
mod download_batches;
