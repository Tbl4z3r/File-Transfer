#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod network;
mod taskbar;
mod taskbar_progress;
use file_transfer_core::{
    BatchStatus, Engine, FolderSelection, SelectedSource, Snapshot, SourceStamp,
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
    let manual = bind_ipv4.trim();
    let candidates = if manual.is_empty() {
        tauri::async_runtime::spawn_blocking(network::candidates)
            .await
            .map_err(|_| "network_unavailable".to_owned())?
    } else {
        let ip = manual
            .parse::<Ipv4Addr>()
            .map_err(|_| "invalid_request".to_owned())?;
        if !network::usable(ip) {
            return Err("invalid_request".to_owned());
        }
        vec![ip]
    };
    if candidates.is_empty() {
        return Err("network_unavailable".to_owned());
    }
    let mut last_error = "network_unavailable".to_owned();
    for bind in candidates {
        match core.inner().start_session_auto(bind).await {
            Ok(info) => return Ok(info),
            Err(error) => {
                if error.code == "busy" {
                    return Err(error.code.to_owned());
                }
                last_error = error.code.to_owned();
            }
        }
    }
    Err(last_error)
}
#[tauri::command]
fn stop_session(core: Core<'_>, window: tauri::Window) {
    core.stop_session();
    taskbar::clear(&window);
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
    expected: Option<SourceStamp>,
) -> Result<Transfer, String> {
    match expected {
        Some(stamp) => core.offer_retry_download(&peer_id, &source, &session_id, &stamp),
        None => core.offer_download_in_session(&peer_id, source, &session_id),
    }
    .map_err(|e| e.code.to_owned())
}
#[tauri::command]
fn offer_download_batch(
    core: Core<'_>,
    peer_id: String,
    sources: Vec<PathBuf>,
    session_id: String,
    expected: Option<Vec<SourceStamp>>,
) -> Result<BatchStatus, String> {
    match expected {
        Some(stamps) => core.offer_retry_download_batch(&peer_id, sources, &session_id, &stamps),
        None => core.offer_download_batch(&peer_id, sources, &session_id),
    }
    .map_err(|e| e.code.to_owned())
}
#[tauri::command]
fn cancel_download_batch(core: Core<'_>, batch_id: String) -> Result<BatchStatus, String> {
    core.cancel_download_batch(&batch_id)
        .map_err(|e| e.code.to_owned())
}
#[tauri::command]
fn cancel_transfer(core: Core<'_>, transfer_id: String) -> Result<Transfer, String> {
    core.cancel(&transfer_id).map_err(|e| e.code.to_owned())
}
type ChosenSource = SelectedSource;
#[tauri::command]
async fn choose_sources(app: tauri::AppHandle) -> Result<Option<Vec<ChosenSource>>, String> {
    let paths = app
        .dialog()
        .file()
        .set_title("Elige los archivos que quieres enviar")
        .blocking_pick_files()
        .map(|paths| {
            paths
                .into_iter()
                .map(|p| p.into_path().map_err(|_| "io_error".to_owned()))
                .collect::<Result<Vec<_>, _>>()
        })
        .transpose()?;
    match paths {
        Some(paths) => inspect_sources(paths).await.map(Some),
        None => Ok(None),
    }
}
#[tauri::command]
async fn inspect_sources(paths: Vec<PathBuf>) -> Result<Vec<ChosenSource>, String> {
    inspect_selection(paths)
        .await
        .map(|selection| selection.sources)
}
#[tauri::command]
async fn inspect_selection(paths: Vec<PathBuf>) -> Result<FolderSelection, String> {
    tauri::async_runtime::spawn_blocking(move || {
        file_transfer_core::inspect_selection(paths).map_err(|e| e.code.to_owned())
    })
    .await
    .map_err(|_| "io_error".to_owned())?
}
#[tauri::command]
async fn choose_source_folder(app: tauri::AppHandle) -> Result<Option<FolderSelection>, String> {
    let selected = app
        .dialog()
        .file()
        .set_title("Elige la carpeta que quieres enviar")
        .blocking_pick_folder()
        .map(|path| path.into_path().map_err(|_| "io_error".to_owned()))
        .transpose()?;
    match selected {
        Some(path) => inspect_selection(vec![path]).await.map(Some),
        None => Ok(None),
    }
}
#[tauri::command]
async fn validate_folder_selection(selection: FolderSelection) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        selection.validate().map_err(|e| e.code.to_owned())
    })
    .await
    .map_err(|_| "io_error".to_owned())?
}
#[tauri::command]
async fn offer_folder_selection(
    core: Core<'_>,
    peer_id: String,
    selection: FolderSelection,
    session_id: String,
) -> Result<BatchStatus, String> {
    let core = core.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        core.offer_folder_selection(&peer_id, selection, &session_id)
            .map_err(|e| e.code.to_owned())
    })
    .await
    .map_err(|_| "offer_uncertain".to_owned())?
}
#[tauri::command]
async fn available_retry_sources(sources: Vec<ChosenSource>) -> Result<Vec<ChosenSource>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        sources
            .into_iter()
            .filter(|source| {
                let Ok(file) = std::fs::File::open(&source.source) else {
                    return false;
                };
                file.metadata().is_ok_and(|metadata| {
                    metadata.is_file()
                        && metadata.modified().is_ok_and(|modified| {
                            source.stamp == SourceStamp::new(metadata.len(), modified)
                        })
                })
            })
            .collect()
    })
    .await
    .map_err(|_| "io_error".to_owned())
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
            let core = Engine::open_ephemeral(directory, "PC personal")?;
            let window = app.get_window("main").ok_or("missing main window")?;
            app.manage(taskbar::start(window, core.clone()));
            app.manage(core);
            Ok(())
        })
        .on_window_event(|window, event| {
            if matches!(
                event,
                tauri::WindowEvent::CloseRequested { .. } | tauri::WindowEvent::Destroyed
            ) {
                window.state::<Arc<Engine>>().stop_session();
                taskbar::clear(window);
                if matches!(event, tauri::WindowEvent::Destroyed) {
                    window.state::<taskbar::TaskbarWorker>().stop();
                }
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
            offer_download_batch,
            cancel_download_batch,
            cancel_transfer,
            choose_sources,
            inspect_sources,
            inspect_selection,
            choose_source_folder,
            validate_folder_selection,
            offer_folder_selection,
            available_retry_sources,
            choose_destination
        ])
        .run(tauri::generate_context!())
        .expect("No se pudo iniciar File Transfer");
}
