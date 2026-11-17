use crate::{ActiveSession, Engine, model::*};
use axum::{
    body::{Body, Bytes},
    http::{HeaderMap, Method, Request, Response, StatusCode, header},
};
use futures_util::StreamExt;
use http_body_util::BodyExt;
use hyper_util::rt::{TokioIo, TokioTimer};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use serde::{Serialize, de::DeserializeOwned};
use sha2::{Digest, Sha256};
use std::{
    convert::Infallible,
    io,
    net::{Ipv4Addr, SocketAddr},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpListener,
    sync::mpsc,
    task::JoinSet,
};
use tokio_rustls::TlsAcceptor;
use tokio_stream::wrappers::ReceiverStream;
use tokio_util::sync::CancellationToken;

#[derive(Clone, Serialize)]
pub struct SessionInfo {
    pub host: String,
    pub port: u16,
    pub server_device_id: String,
    pub cert_sha256: String,
}

pub(crate) async fn start(engine: Arc<Engine>, bind: Ipv4Addr, port: u16) -> Result<SessionInfo> {
    if bind.is_multicast() || bind.is_unspecified() || bind == Ipv4Addr::BROADCAST || port < 1024 {
        return Err(Fault::new(400, "invalid_request"));
    }
    let closing = engine.lock().closing.clone();
    if let Some(closed) = closing {
        tokio::time::timeout(Duration::from_secs(15), async {
            while !closed.load(Ordering::Acquire) {
                tokio::time::sleep(Duration::from_millis(1)).await;
            }
        })
        .await
        .map_err(|_| Fault::new(409, "busy"))?;
    }
    // Bind and reserve state atomically after the previous listener has actually closed.
    let cancel = CancellationToken::new();
    let closed = Arc::new(AtomicBool::new(false));
    let info = SessionInfo {
        host: bind.to_string(),
        port,
        server_device_id: engine.identity.id.clone(),
        cert_sha256: engine.cert_sha256(),
    };
    let mut inner = engine.lock();
    if inner.session.is_some() {
        return Err(Fault::new(409, "busy"));
    }
    let listener =
        std::net::TcpListener::bind(SocketAddr::from((bind, port))).map_err(|e| io_fault(&e))?;
    listener.set_nonblocking(true).map_err(|e| io_fault(&e))?;
    let listener = TcpListener::from_std(listener).map_err(|e| io_fault(&e))?;
    let config = rustls::ServerConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
    .map_err(|_| Fault::new(500, "identity_invalid"))?
    .with_no_client_auth()
    .with_single_cert(
        vec![CertificateDer::from(engine.cert.clone())],
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(engine.key.clone())),
    )
    .map_err(|_| Fault::new(500, "identity_invalid"))?;
    inner.session = Some(ActiveSession {
        info: info.clone(),
        cancel: cancel.clone(),
        closed: closed.clone(),
    });
    inner.closing = None;
    inner.receive = false;
    inner.records.clear();
    inner.window = None;
    drop(inner);
    let acceptor = TlsAcceptor::from(Arc::new(config));
    tokio::spawn(async move {
        let mut connections = JoinSet::new();
        let mut ticker = tokio::time::interval(Duration::from_millis(100));
        loop {
            tokio::select! {
                biased;
                _ = cancel.cancelled() => break,
                _ = ticker.tick() => engine.expire(),
                Some(_) = connections.join_next(), if !connections.is_empty() => {},
                accepted = listener.accept() => {
                    let Ok((socket, _)) = accepted else { break; };
                    // Bound idle/untrusted connections as well as transfer memory.
                    if connections.len() >= 32 { drop(socket); continue; }
                    let acceptor = acceptor.clone(); let core = engine.clone();
                    let header_timeout = core.timeouts.headers;
                    connections.spawn(async move {
                        let Ok(Ok(tls)) = tokio::time::timeout(core.timeouts.headers, acceptor.accept(socket)).await else { return; };
                        let service = hyper::service::service_fn(move |request: Request<hyper::body::Incoming>| {
                            let core = core.clone();
                            async move { Ok::<_, Infallible>(dispatch(core, request.map(Body::new)).await) }
                        });
                        let mut builder = hyper::server::conn::http1::Builder::new();
                        builder.timer(TokioTimer::new()).header_read_timeout(header_timeout).max_buf_size(BUFFER_SIZE).keep_alive(true);
                        let _ = builder.serve_connection(TokioIo::new(tls), service).await;
                    });
                }
            }
        }
        drop(listener);
        connections.abort_all();
        while connections.join_next().await.is_some() {}
        closed.store(true, Ordering::Release);
    });
    Ok(info)
}

fn json_response(status: u16, data: &impl Serialize) -> Response<Body> {
    let bytes = serde_json::to_vec(data).expect("serializable protocol response");
    if bytes.len() > 16 * 1024 {
        return fault_response(Fault::new(500, "io_error"));
    }
    Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, "application/json")
        .header(header::CACHE_CONTROL, "no-store")
        .body(Body::from(bytes))
        .unwrap()
}
fn fault_response(fault: Fault) -> Response<Body> {
    let message = match fault.code {
        "unauthorized" => "Dispositivo no autorizado.",
        "pairing_denied" => "Emparejamiento no autorizado.",
        "receive_disabled" => "Activa Recibir archivos en el PC.",
        "busy" => "Hay otra transferencia pendiente o activa.",
        "integrity_mismatch" => "Los hashes no coinciden.",
        "insufficient_space" => "No hay espacio suficiente.",
        "source_changed" => "El archivo fuente cambió; vuelve a enviarlo.",
        "invalid_name" => "El nombre de archivo no es válido.",
        "timeout" => "Se agotó el tiempo de espera.",
        "io_error" => "No se pudo acceder al archivo.",
        _ => "La operación no está disponible o la solicitud no es válida.",
    };
    json_response(
        fault.status,
        &serde_json::json!({"error":{"code":fault.code,"message":message}}),
    )
}
fn unique_header<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    let mut values = headers.get_all(name).iter();
    let first = values.next()?.to_str().ok()?;
    if values.next().is_some() {
        None
    } else {
        Some(first)
    }
}
fn no_encoding(headers: &HeaderMap) -> Result<()> {
    if headers.contains_key(header::CONTENT_ENCODING)
        && unique_header(headers, "content-encoding") != Some("identity")
    {
        return Err(Fault::new(415, "unsupported_media_type"));
    }
    if headers.contains_key(header::TRAILER) || headers.contains_key(header::RANGE) {
        return Err(Fault::new(400, "invalid_request"));
    }
    Ok(())
}
fn media(headers: &HeaderMap, expected: &str) -> Result<()> {
    let value =
        unique_header(headers, "content-type").ok_or(Fault::new(415, "unsupported_media_type"))?;
    if value
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .eq_ignore_ascii_case(expected)
    {
        Ok(())
    } else {
        Err(Fault::new(415, "unsupported_media_type"))
    }
}
async fn parse_json<T: DeserializeOwned>(request: Request<Body>, timeout: Duration) -> Result<T> {
    media(request.headers(), "application/json")?;
    if unique_header(request.headers(), "content-length")
        .and_then(|n| n.parse::<u64>().ok())
        .is_some_and(|n| n > 16384)
    {
        return Err(Fault::new(413, "limit_exceeded"));
    }
    let mut body = request.into_body();
    let data = tokio::time::timeout(timeout, async {
        let mut data = Vec::new();
        while let Some(frame) = body.frame().await {
            let frame = frame.map_err(|_| Fault::new(400, "invalid_request"))?;
            if frame.is_trailers() {
                return Err(Fault::new(400, "invalid_request"));
            }
            if let Ok(chunk) = frame.into_data() {
                if data.len() + chunk.len() > 16_384 {
                    return Err(Fault::new(413, "limit_exceeded"));
                }
                data.extend_from_slice(&chunk);
            }
        }
        Ok(data)
    })
    .await
    .map_err(|_| Fault::new(400, "invalid_request"))??;
    // Struct deserialization rejects duplicate keys, unknown fields, null, invalid UTF-8 and trailing JSON.
    serde_json::from_slice(&data).map_err(|_| Fault::new(400, "invalid_request"))
}
async fn dispatch(engine: Arc<Engine>, request: Request<Body>) -> Response<Body> {
    match route(engine, request).await {
        Ok(response) => response,
        Err(error) => fault_response(error),
    }
}
async fn route(engine: Arc<Engine>, request: Request<Body>) -> Result<Response<Body>> {
    let path = request.uri().path().to_owned();
    let method = request.method().clone();
    if !path.starts_with("/v1/") {
        return Err(Fault::new(404, "unsupported_version"));
    }
    if request.uri().query().is_some() {
        return Err(Fault::new(400, "invalid_request"));
    }
    no_encoding(request.headers())?;
    let mut expected_method = Method::GET;
    let route = if path == "/v1/pair" {
        expected_method = Method::POST;
        "pair"
    } else if path == "/v1/transfers" {
        expected_method = Method::POST;
        "create"
    } else if path == "/v1/offers" {
        "offers"
    } else if path.starts_with("/v1/transfers/") {
        "transfer"
    } else {
        return Err(Fault::new(404, "not_found"));
    };
    if route != "transfer" && method != expected_method {
        return Err(Fault::new(405, "method_not_allowed"));
    }
    if route == "pair" {
        let request = parse_json::<PairRequest>(request, engine.timeouts.headers).await?;
        return Ok(json_response(201, &engine.pair(request)?));
    }
    let peer = engine.authenticate(
        unique_header(request.headers(), "x-device-id").unwrap_or(""),
        unique_header(request.headers(), "authorization")
            .and_then(|v| v.strip_prefix("Bearer "))
            .unwrap_or(""),
    )?;
    if route == "create" {
        let request = parse_json::<CreateUpload>(request, engine.timeouts.headers).await?;
        return Ok(json_response(201, &engine.create_upload(&peer, request)?));
    }
    if route == "offers" {
        return Ok(json_response(
            200,
            &serde_json::json!({"transfers":engine.offers(&peer)?}),
        ));
    }
    let pieces: Vec<_> = path
        .trim_start_matches("/v1/transfers/")
        .split('/')
        .collect();
    if pieces.is_empty() || pieces.len() > 2 || !valid_id(pieces[0]) {
        return Err(Fault::new(404, "not_found"));
    }
    let id = pieces[0];
    let operation = pieces.get(1).copied().unwrap_or("");
    // Authenticate ownership before direction/state errors so foreign IDs never leak.
    let transfer = engine.status(&peer, id)?;
    match operation {
        "" if method == Method::GET => Ok(json_response(200, &transfer)),
        "accept" if method == Method::POST => {
            parse_json::<Empty>(request, engine.timeouts.headers).await?;
            Ok(json_response(200, &engine.accept_download(&peer, id)?))
        }
        "cancel" if method == Method::POST => {
            parse_json::<Empty>(request, engine.timeouts.headers).await?;
            Ok(json_response(200, &engine.cancel_remote(&peer, id)?))
        }
        "complete" if method == Method::POST => {
            let data = parse_json::<CompleteRequest>(request, engine.timeouts.headers).await?;
            Ok(json_response(
                200,
                &engine.complete(&peer, id, &data.sha256)?,
            ))
        }
        "content" if method == Method::PUT => upload(engine, &peer, id, request).await,
        "content" if method == Method::GET => download(engine, &peer, id).await,
        "" | "accept" | "cancel" | "complete" | "content" => {
            Err(Fault::new(405, "method_not_allowed"))
        }
        _ => Err(Fault::new(404, "not_found")),
    }
}
async fn upload(
    engine: Arc<Engine>,
    peer: &str,
    id: &str,
    request: Request<Body>,
) -> Result<Response<Body>> {
    media(request.headers(), "application/octet-stream")?;
    if request.headers().contains_key(header::TRANSFER_ENCODING) {
        return Err(Fault::new(400, "invalid_request"));
    }
    let length = unique_header(request.headers(), "content-length")
        .ok_or(Fault::new(411, "length_required"))?
        .parse::<u64>()
        .map_err(|_| Fault::new(400, "invalid_request"))?;
    if length > MAX_SIZE {
        return Err(Fault::new(413, "limit_exceeded"));
    }
    let expected = engine.status(peer, id)?.size_bytes.parse::<u64>().unwrap();
    if length != expected {
        return Err(Fault::new(400, "size_mismatch"));
    }
    let (file, cancel, size) = engine.claim_stream(peer, id, Direction::Upload)?;
    let result = write_upload(
        &engine,
        peer,
        id,
        request.into_body(),
        tokio::fs::File::from_std(file),
        cancel,
        size,
    )
    .await;
    match result {
        Ok(transfer) => Ok(json_response(200, &transfer)),
        Err(error) => {
            engine.fail_stream(id, error.code);
            Err(error)
        }
    }
}
async fn write_upload(
    engine: &Engine,
    peer: &str,
    id: &str,
    body: Body,
    mut file: tokio::fs::File,
    cancel: CancellationToken,
    expected: u64,
) -> Result<Transfer> {
    let mut stream = body.into_data_stream();
    let mut count = 0u64;
    let mut hash = Sha256::new();
    loop {
        let frame = tokio::select! { biased; _ = cancel.cancelled() => return Err(Fault::new(409, "invalid_state")), frame = tokio::time::timeout(engine.timeouts.idle, stream.next()) => frame.map_err(|_| Fault::new(500, "timeout"))? };
        let Some(frame) = frame else {
            break;
        };
        let bytes = frame.map_err(|_| Fault::new(400, "size_mismatch"))?;
        for chunk in bytes.chunks(BUFFER_SIZE) {
            if count + chunk.len() as u64 > expected {
                return Err(Fault::new(400, "size_mismatch"));
            }
            tokio::select! { biased; _ = cancel.cancelled() => return Err(Fault::new(409, "invalid_state")), written = tokio::time::timeout(engine.timeouts.idle, file.write_all(chunk)) => written.map_err(|_| Fault::new(500, "timeout"))?.map_err(|e| io_fault(&e))? }
            hash.update(chunk);
            count += chunk.len() as u64;
            engine.progress(peer, id, count)?;
        }
    }
    if count != expected {
        return Err(Fault::new(400, "size_mismatch"));
    }
    tokio::select! { biased; _ = cancel.cancelled() => return Err(Fault::new(409, "invalid_state")), flushed = tokio::time::timeout(engine.timeouts.idle, async { file.flush().await?; file.sync_all().await }) => flushed.map_err(|_| Fault::new(500, "timeout"))?.map_err(|e| io_fault(&e))? }
    engine.progress(peer, id, count)?;
    engine.finish_stream(peer, id, format!("{:x}", hash.finalize()))
}
async fn download(engine: Arc<Engine>, peer: &str, id: &str) -> Result<Response<Body>> {
    let (file, cancel, expected) = engine.claim_stream(peer, id, Direction::Download)?;
    let (sender, receiver) = mpsc::channel::<std::result::Result<Bytes, io::Error>>(1);
    let owner = peer.to_owned();
    let transfer = id.to_owned();
    tokio::spawn(async move {
        let result = read_download(
            &engine,
            &owner,
            &transfer,
            tokio::fs::File::from_std(file),
            &cancel,
            expected,
            &sender,
        )
        .await;
        if let Err(error) = result {
            engine.fail_stream(&transfer, error.code);
            let _ = sender.try_send(Err(io::Error::other(error.code)));
        }
    });
    Ok(Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "application/octet-stream")
        .header(header::CONTENT_LENGTH, expected)
        .header(header::CACHE_CONTROL, "no-store")
        .body(Body::from_stream(ReceiverStream::new(receiver)))
        .unwrap())
}
async fn read_download(
    engine: &Engine,
    peer: &str,
    id: &str,
    mut file: tokio::fs::File,
    cancel: &CancellationToken,
    expected: u64,
    sender: &mpsc::Sender<std::result::Result<Bytes, io::Error>>,
) -> Result<()> {
    let mut buffer = vec![0u8; BUFFER_SIZE];
    let mut count = 0u64;
    let mut hash = Sha256::new();
    loop {
        let read = tokio::select! { biased; _ = cancel.cancelled() => return Err(Fault::new(409, "invalid_state")), read = tokio::time::timeout(engine.timeouts.idle, file.read(&mut buffer)) => read.map_err(|_| Fault::new(500, "timeout"))?.map_err(|e| io_fault(&e))? };
        if read == 0 {
            break;
        }
        if count + read as u64 > expected {
            return Err(Fault::new(409, "source_changed"));
        }
        let bytes = Bytes::copy_from_slice(&buffer[..read]);
        tokio::select! { biased; _ = cancel.cancelled() => return Err(Fault::new(409, "invalid_state")), sent = tokio::time::timeout(engine.timeouts.idle, sender.send(Ok(bytes))) => sent.map_err(|_| Fault::new(500, "timeout"))?.map_err(|_| Fault::new(500, "network_error"))? }
        hash.update(&buffer[..read]);
        count += read as u64;
        engine.progress(peer, id, count)?;
    }
    if count != expected {
        return Err(Fault::new(409, "source_changed"));
    }
    engine.finish_stream(peer, id, format!("{:x}", hash.finalize()))?;
    Ok(())
}
