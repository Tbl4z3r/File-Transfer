use crate::taskbar_progress::{Feedback, feedback};
use file_transfer_core::Engine;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};

pub struct TaskbarWorker(Arc<AtomicBool>);
impl TaskbarWorker {
    pub fn stop(&self) {
        self.0.store(true, Ordering::Release);
    }
}
pub fn start(window: tauri::Window, core: Arc<Engine>) -> TaskbarWorker {
    let stopped = Arc::new(AtomicBool::new(false));
    let worker_stopped = stopped.clone();
    std::thread::spawn(move || {
        let mut previous = None;
        while !worker_stopped.load(Ordering::Acquire) {
            let next = feedback(&core.snapshot());
            if previous.as_ref() != Some(&next) {
                apply(&window, &next);
                previous = Some(next);
            }
            std::thread::sleep(std::time::Duration::from_millis(500));
        }
        clear(&window);
    });
    TaskbarWorker(stopped)
}
pub fn clear(window: &tauri::Window) {
    apply(window, &Feedback::Clear);
}
fn apply(window: &tauri::Window, feedback: &Feedback) {
    #[cfg(windows)]
    {
        use tauri::window::{ProgressBarState, ProgressBarStatus};
        let (status, progress) = match feedback {
            Feedback::Clear => (ProgressBarStatus::None, None),
            Feedback::Waiting => (ProgressBarStatus::Indeterminate, None),
            Feedback::Progress(value) => (ProgressBarStatus::Normal, Some(*value)),
        };
        // Shell integration is best effort and must never interrupt a transfer.
        let _ = window.set_progress_bar(ProgressBarState {
            status: Some(status),
            progress,
        });
    }
    #[cfg(not(windows))]
    let _ = (window, feedback);
}
