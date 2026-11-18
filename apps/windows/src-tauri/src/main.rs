#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use file_transfer_core::{
    BatchStatus, Engine, Snapshot,
    model::{Descriptor, Transfer},
};
use std::{net::Ipv4Addr, path::PathBuf, sync::Arc};
use tauri::{Manager, State};
use tauri_plugin_dialog::DialogExt;

type Core<'a> = State<'a, Arc<Engine>>;
#[tauri::command]
async fn start_session(
    core: Core<'_>,
    bind_ipv4: String,
) -> Result<file_transfer_core::SessionInfo, String> {
    let bind = bind_ipv4
        .parse::<Ipv4Addr>()
        .map_err(|_| "invalid_request".to_owned())?;
    core.inner()
        .start_session_auto(bind)
        .await
        .map_err(|e| e.code.to_owned())
}
#[tauri::command]
fn stop_session(core: Core<'_>) {
    core.stop_session();
}
#[tauri::command]
fn open_pairing_window(core: Core<'_>) -> Result<Descriptor, String> {
    core.open_pairing_window().map_err(|e| e.code.to_owned())
}
#[tauri::command]
fn set_receive_enabled(core: Core<'_>, enabled: bool) -> Result<(), String> {
    core.set_receive_enabled(enabled)
        .map_err(|e| e.code.to_owned())
}
#[tauri::command]
fn snapshot(core: Core<'_>) -> Snapshot {
    core.snapshot()
}
#[tauri::command]
fn revoke_peer(core: Core<'_>, peer_id: String) -> Result<(), String> {
    core.revoke_peer(&peer_id).map_err(|e| e.code.to_owned())
}
#[tauri::command]
fn accept_upload(
    core: Core<'_>,
    transfer_id: String,
    destination: PathBuf,
) -> Result<Transfer, String> {
    core.accept_upload(&transfer_id, destination)
        .map_err(|e| e.code.to_owned())
}
#[tauri::command]
fn accept_upload_batch(
    core: Core<'_>,
    batch_id: String,
    destination: PathBuf,
) -> Result<BatchStatus, String> {
    core.accept_upload_batch(&batch_id, destination)
        .map_err(|e| e.code.to_owned())
}
#[tauri::command]
fn cancel_upload_batch(core: Core<'_>, batch_id: String) -> Result<BatchStatus, String> {
    core.cancel_upload_batch(&batch_id)
        .map_err(|e| e.code.to_owned())
}
#[tauri::command]
fn offer_download(
    core: Core<'_>,
    peer_id: String,
    source: PathBuf,
    session_id: String,
) -> Result<Transfer, String> {
    core.offer_download_in_session(&peer_id, source, &session_id)
        .map_err(|e| e.code.to_owned())
}
#[tauri::command]
fn cancel_transfer(core: Core<'_>, transfer_id: String) -> Result<Transfer, String> {
    core.cancel(&transfer_id).map_err(|e| e.code.to_owned())
}
#[derive(serde::Serialize)]
struct ChosenSource {
    source: PathBuf,
    file_name: String,
    size_bytes: String,
}
#[tauri::command]
async fn choose_sources(app: tauri::AppHandle) -> Result<Option<Vec<ChosenSource>>, String> {
    app.dialog()
        .file()
        .set_title("Elige los archivos que quieres enviar")
        .blocking_pick_files()
        .map(|paths| {
            paths
                .into_iter()
                .map(|p| {
                    let source = p.into_path().map_err(|_| "io_error".to_owned())?;
                    let metadata = std::fs::metadata(&source).map_err(|_| "io_error".to_owned())?;
                    if !metadata.is_file() {
                        return Err("io_error".to_owned());
                    }
                    let file_name = source
                        .file_name()
                        .and_then(|name| name.to_str())
                        .ok_or_else(|| "invalid_name".to_owned())?
                        .to_owned();
                    Ok(ChosenSource {
                        source,
                        file_name,
                        size_bytes: metadata.len().to_string(),
                    })
                })
                .collect()
        })
        .transpose()
}
#[tauri::command]
async fn choose_destination(app: tauri::AppHandle) -> Result<Option<PathBuf>, String> {
    app.dialog()
        .file()
        .set_title("Elige dónde guardar el archivo")
        .blocking_pick_folder()
        .map(|p| p.into_path().map_err(|_| "io_error".to_owned()))
        .transpose()
}
fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let directory = app.path().app_data_dir()?;
            let core = Engine::open(directory, "PC personal")?;
            app.manage(core);
            Ok(())
        })
        .on_window_event(|window, event| {
            if matches!(
                event,
                tauri::WindowEvent::CloseRequested { .. } | tauri::WindowEvent::Destroyed
            ) {
                window.state::<Arc<Engine>>().stop_session();
            }
        })
        .invoke_handler(tauri::generate_handler![
            start_session,
            stop_session,
            open_pairing_window,
            set_receive_enabled,
            snapshot,
            revoke_peer,
            accept_upload,
            accept_upload_batch,
            cancel_upload_batch,
            offer_download,
            cancel_transfer,
            choose_sources,
            choose_destination
        ])
        .run(tauri::generate_context!())
        .expect("No se pudo iniciar File Transfer");
}
