use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const MAX_SIZE: u64 = 1_099_511_627_776;
pub const BUFFER_SIZE: usize = 64 * 1024;
pub const TLS_HOST: &str = "file-transfer.local";

pub fn valid_id(value: &str) -> bool {
    uuid::Uuid::parse_str(value).is_ok_and(|id| {
        id.get_version_num() == 4
            && id.get_variant() == uuid::Variant::RFC4122
            && id.to_string() == value
    })
}
pub fn parse_size(value: &str) -> Option<u64> {
    if value.is_empty()
        || (value.len() > 1 && value.starts_with('0'))
        || !value.bytes().all(|c| c.is_ascii_digit())
    {
        return None;
    }
    value.parse().ok().filter(|n| *n <= MAX_SIZE)
}
pub fn valid_hash(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
pub fn valid_device_name(value: &str) -> bool {
    (1..=64).contains(&value.chars().count())
        && !value.chars().any(|c| c <= '\u{1f}' || c == '\u{7f}')
}
pub fn valid_name(value: &str) -> bool {
    if value.is_empty()
        || value.len() > 180
        || value.ends_with([' ', '.'])
        || value
            .chars()
            .any(|c| c <= '\u{1f}' || c == '\u{7f}' || "/\\:*?\"<>|".contains(c))
    {
        return false;
    }
    let stem = value.split('.').next().unwrap_or("").to_uppercase();
    if ["CON", "PRN", "AUX", "NUL"].contains(&stem.as_str()) {
        return false;
    }
    for prefix in ["COM", "LPT"] {
        if let Some(tail) = stem.strip_prefix(prefix)
            && ["1", "2", "3", "4", "5", "6", "7", "8", "9", "¹", "²", "³"].contains(&tail)
        {
            return false;
        }
    }
    value != "." && value != ".."
}
pub fn digest(data: &[u8]) -> String {
    format!("{:x}", Sha256::digest(data))
}
pub fn new_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairRequest {
    pub pairing_token: String,
    pub device_id: String,
    pub device_name: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairResponse {
    pub protocol_version: u8,
    pub server_device_id: String,
    pub server_name: String,
    pub credential: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Descriptor {
    pub v: u8,
    pub host: String,
    pub port: u16,
    pub server_device_id: String,
    pub cert_sha256: String,
    pub pairing_token: String,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CreateUpload {
    pub file_name: String,
    pub size_bytes: String,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CompleteRequest {
    pub sha256: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Empty {}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Direction {
    Upload,
    Download,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TransferState {
    AwaitingAcceptance,
    Ready,
    Streaming,
    AwaitingVerification,
    Completed,
    Cancelled,
    Failed,
}
impl TransferState {
    pub fn terminal(self) -> bool {
        matches!(self, Self::Completed | Self::Cancelled | Self::Failed)
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Transfer {
    pub transfer_id: String,
    pub direction: Direction,
    pub file_name: String,
    pub size_bytes: String,
    pub state: TransferState,
    pub bytes_transferred: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sha256: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_code: Option<String>,
}
#[derive(Debug, Clone)]
pub struct Fault {
    pub status: u16,
    pub code: &'static str,
}
impl Fault {
    pub fn new(status: u16, code: &'static str) -> Self {
        Self { status, code }
    }
}
impl std::fmt::Display for Fault {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.code)
    }
}
impl std::error::Error for Fault {}
pub type Result<T> = std::result::Result<T, Fault>;
pub fn io_fault(error: &std::io::Error) -> Fault {
    if error.kind() == std::io::ErrorKind::StorageFull {
        Fault::new(507, "insufficient_space")
    } else {
        Fault::new(500, "io_error")
    }
}
