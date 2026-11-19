#!/usr/bin/env bash
# Run the actual snapshot calculation without Tauri's Linux GTK/WebKit dependencies.
set -euo pipefail
windows_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
test_dir=$(mktemp -d /tmp/ft-taskbar-test.XXXXXX)
trap 'rm -rf "$test_dir"' EXIT
mkdir "$test_dir/src"
cat > "$test_dir/Cargo.toml" <<TOML
[package]
name = "ft-taskbar-headless-test"
version = "0.0.0"
edition = "2024"
[dependencies]
file-transfer-core = { path = "$windows_dir/core" }
[profile.dev]
debug = 0
incremental = false
TOML
cat > "$test_dir/src/main.rs" <<RS
#[path = "$windows_dir/src-tauri/src/taskbar_progress.rs"]
mod taskbar_progress;
fn main() {}
RS
cp "$windows_dir/core/Cargo.lock" "$test_dir/Cargo.lock"
CARGO_INCREMENTAL=0 CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-/tmp/ft-windows-lotes-target}" cargo test --manifest-path "$test_dir/Cargo.toml" --offline
