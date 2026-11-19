use crate::model::*;
use base64::{Engine as _, engine::general_purpose::STANDARD};
use rcgen::{
    CertificateParams, ExtendedKeyUsagePurpose, KeyPair, KeyUsagePurpose, PKCS_ECDSA_P256_SHA256,
};
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, File},
    io::Write,
    path::{Path, PathBuf},
};
use tempfile::{Builder, NamedTempFile};

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Identity {
    pub id: String,
    pub name: String,
    pub cert: String,
    pub key: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct PeerRecord {
    pub id: String,
    pub name: String,
    pub credential_hash: String,
}
#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Registry {
    pub peers: Vec<PeerRecord>,
    pub partials: Vec<PathBuf>,
}

pub(crate) fn private_directory(path: &Path) -> std::io::Result<()> {
    fs::create_dir_all(path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}
fn private_file(file: &File) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        file.set_permissions(fs::Permissions::from_mode(0o600))?;
    }
    #[cfg(not(unix))]
    let _ = file;
    Ok(())
}
pub(crate) fn save<T: Serialize>(path: &Path, value: &T) -> std::io::Result<()> {
    let parent = path
        .parent()
        .ok_or_else(|| std::io::Error::other("storage"))?;
    let mut temp = NamedTempFile::new_in(parent)?;
    private_file(temp.as_file())?;
    serde_json::to_writer(temp.as_file_mut(), value)?;
    temp.as_file_mut().flush()?;
    temp.as_file().sync_all()?;
    temp.persist(path).map_err(|e| e.error)?;
    Ok(())
}
pub(crate) fn identity(directory: &Path, name: &str) -> Result<Identity> {
    let path = directory.join("identity.json");
    if path.exists() {
        let bytes = fs::read(&path).map_err(|e| io_fault(&e))?;
        let identity: Identity =
            serde_json::from_slice(&bytes).map_err(|_| Fault::new(500, "identity_invalid"))?;
        if !valid_id(&identity.id) || !valid_device_name(&identity.name) {
            return Err(Fault::new(500, "identity_invalid"));
        }
        return Ok(identity);
    }
    if !valid_device_name(name) {
        return Err(Fault::new(400, "invalid_request"));
    }
    let key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256)
        .map_err(|_| Fault::new(500, "identity_invalid"))?;
    let mut params = CertificateParams::new(vec![TLS_HOST.to_owned()])
        .map_err(|_| Fault::new(500, "identity_invalid"))?;
    params.not_before = time::OffsetDateTime::now_utc() - time::Duration::minutes(5);
    params.not_after = time::OffsetDateTime::now_utc() + time::Duration::days(365);
    params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
    params.extended_key_usages = vec![ExtendedKeyUsagePurpose::ServerAuth];
    let cert = params
        .self_signed(&key)
        .map_err(|_| Fault::new(500, "identity_invalid"))?;
    let value = Identity {
        id: new_id(),
        name: name.into(),
        cert: STANDARD.encode(cert.der()),
        key: STANDARD.encode(key.serialize_der()),
    };
    // A storage lock owned by Engine prevents concurrent creators.
    save(&path, &value).map_err(|e| io_fault(&e))?;
    Ok(value)
}
pub(crate) fn registry(directory: &Path) -> Result<Registry> {
    let path = directory.join("peers.json");
    if !path.exists() {
        return Ok(Registry::default());
    }
    let data = fs::read(path).map_err(|e| io_fault(&e))?;
    let registry: Registry =
        serde_json::from_slice(&data).map_err(|_| Fault::new(500, "store_invalid"))?;
    if registry
        .peers
        .iter()
        .any(|p| !valid_id(&p.id) || !valid_hash(&p.credential_hash) || !valid_device_name(&p.name))
    {
        return Err(Fault::new(500, "store_invalid"));
    }
    Ok(registry)
}
pub(crate) fn clean_orphans(registry: &mut Registry) -> Result<()> {
    for path in &registry.partials {
        // Only exact app-journaled temporary paths; no directory scans or remote paths.
        let owned_name = path
            .file_name()
            .and_then(|n| n.to_str())
            .is_some_and(|n| n.starts_with(".file-transfer-") && n.ends_with(".part"));
        if owned_name
            && let Ok(metadata) = fs::symlink_metadata(path)
            && metadata.is_file()
            && !metadata.file_type().is_symlink()
        {
            fs::remove_file(path).map_err(|e| io_fault(&e))?;
        }
    }
    registry.partials.clear();
    Ok(())
}
pub(crate) fn partial(directory: &Path) -> std::io::Result<NamedTempFile> {
    let temp = Builder::new()
        .prefix(".file-transfer-")
        .suffix(".part")
        .tempfile_in(directory)?;
    private_file(temp.as_file())?;
    Ok(temp)
}
pub(crate) fn publish(
    mut temp: NamedTempFile,
    directory: &Path,
    name: &str,
) -> std::io::Result<PathBuf> {
    // Directory chosen locally, canonicalized at acceptance and revalidated here.
    if fs::canonicalize(directory)? != directory {
        return Err(std::io::Error::other("destination changed"));
    }
    let (stem, extension) = match name.rsplit_once('.') {
        Some((stem, ext)) if !stem.is_empty() => (stem, format!(".{ext}")),
        _ => (name, String::new()),
    };
    for index in 0..10_000 {
        let suffix = if index == 0 {
            String::new()
        } else {
            format!(" ({index})")
        };
        let max_stem = 240usize.saturating_sub(extension.len() + suffix.len());
        let mut end = stem.len().min(max_stem);
        while !stem.is_char_boundary(end) {
            end -= 1;
        }
        let candidate = directory.join(format!("{}{}{}", &stem[..end], suffix, extension));
        match temp.persist_noclobber(&candidate) {
            Ok(_) => return Ok(candidate),
            Err(error) if error.error.kind() == std::io::ErrorKind::AlreadyExists => {
                temp = error.file
            }
            Err(error) => return Err(error.error),
        }
    }
    Err(std::io::Error::other("destination collisions"))
}
