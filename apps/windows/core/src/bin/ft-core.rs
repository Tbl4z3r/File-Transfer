//! Harness local para interoperabilidad: stdin/stdout privado, nunca administración HTTP.
use file_transfer_core::{Engine, model::Fault};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{net::Ipv4Addr, path::PathBuf};
use tokio::io::{AsyncBufReadExt, BufReader};

#[derive(Deserialize)]
#[serde(tag = "command", rename_all = "snake_case", deny_unknown_fields)]
enum Command {
    Pairing,
    Receive {
        enabled: bool,
    },
    Peers,
    Revoke {
        peer_id: String,
    },
    AcceptUpload {
        transfer_id: String,
        destination: PathBuf,
    },
    AcceptUploadBatch {
        batch_id: String,
        destination: PathBuf,
    },
    CancelUploadBatch {
        batch_id: String,
    },
    Offer {
        peer_id: String,
        source: PathBuf,
    },
    OfferDownloadBatch {
        peer_id: String,
        sources: Vec<PathBuf>,
        session_id: String,
    },
    OfferFolderBatch {
        peer_id: String,
        sources: Vec<PathBuf>,
        session_id: String,
    },
    CancelDownloadBatch {
        batch_id: String,
    },
    Cancel {
        transfer_id: String,
    },
    Snapshot,
    Stop,
}
#[tokio::main]
async fn main() -> std::result::Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args().collect();
    if !(args.len() == 8 || (args.len() == 9 && args[8] == "--ephemeral-peers"))
        || args[1] != "serve"
        || args[2] != "--data"
        || args[4] != "--bind"
        || args[6] != "--port"
    {
        eprintln!(
            "Uso: ft-core serve --data <directorio privado> --bind <IPv4 local> --port <1024..65535> [--ephemeral-peers]"
        );
        std::process::exit(2);
    }
    let core = if args.len() == 9 {
        Engine::open_ephemeral(&args[3], "PC personal")?
    } else {
        Engine::open(&args[3], "PC personal")?
    };
    let info = core
        .start_session(args[5].parse::<Ipv4Addr>()?, args[7].parse()?)
        .await?;
    println!("{}", json!({"event":"started","session":info}));
    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    loop {
        let line = tokio::select! { line = lines.next_line() => line?, _ = tokio::signal::ctrl_c() => None };
        let Some(line) = line else {
            break;
        };
        // Descriptor/credential never written to a log: stdout is the explicit local control channel.
        let parsed = serde_json::from_str::<Command>(&line);
        let stopping = matches!(parsed, Ok(Command::Stop));
        let result: std::result::Result<Value, Fault> = (|| {
            Ok(
                match parsed.map_err(|_| Fault::new(400, "invalid_request"))? {
                    Command::Pairing => serde_json::to_value(core.open_pairing_window()?).unwrap(),
                    Command::Receive { enabled } => {
                        core.set_receive_enabled(enabled)?;
                        json!({})
                    }
                    Command::Peers => serde_json::to_value(core.list_peers()).unwrap(),
                    Command::Revoke { peer_id } => {
                        core.revoke_peer(&peer_id)?;
                        json!({})
                    }
                    Command::AcceptUpload {
                        transfer_id,
                        destination,
                    } => serde_json::to_value(core.accept_upload(&transfer_id, destination)?)
                        .unwrap(),
                    Command::AcceptUploadBatch {
                        batch_id,
                        destination,
                    } => serde_json::to_value(core.accept_upload_batch(&batch_id, destination)?)
                        .unwrap(),
                    Command::CancelUploadBatch { batch_id } => {
                        serde_json::to_value(core.cancel_upload_batch(&batch_id)?).unwrap()
                    }
                    Command::Offer { peer_id, source } => {
                        serde_json::to_value(core.offer_download(&peer_id, source)?).unwrap()
                    }
                    Command::OfferDownloadBatch {
                        peer_id,
                        sources,
                        session_id,
                    } => serde_json::to_value(core.offer_download_batch(
                        &peer_id,
                        sources,
                        &session_id,
                    )?)
                    .unwrap(),
                    Command::OfferFolderBatch {
                        peer_id,
                        sources,
                        session_id,
                    } => serde_json::to_value(core.offer_folder_batch(
                        &peer_id,
                        sources,
                        &session_id,
                    )?)
                    .unwrap(),
                    Command::CancelDownloadBatch { batch_id } => {
                        serde_json::to_value(core.cancel_download_batch(&batch_id)?).unwrap()
                    }
                    Command::Cancel { transfer_id } => {
                        serde_json::to_value(core.cancel(&transfer_id)?).unwrap()
                    }
                    Command::Snapshot => serde_json::to_value(core.snapshot()).unwrap(),
                    Command::Stop => {
                        core.stop_session();
                        json!({})
                    }
                },
            )
        })();
        match result {
            Ok(result) => println!("{}", json!({"ok":true,"result":result})),
            Err(error) => println!("{}", json!({"ok":false,"error":{"code":error.code}})),
        }
        if stopping {
            break;
        }
    }
    core.stop_session();
    Ok(())
}
