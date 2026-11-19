//! Folder manifests and bounded local filesystem selection. No remote path is a local path.
use crate::*;
use serde::{Deserialize, Serialize};

pub(crate) fn optional_path<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> std::result::Result<Option<String>, D::Error> {
    String::deserialize(deserializer).map(Some)
}
pub(crate) fn nonempty_directories<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> std::result::Result<Vec<String>, D::Error> {
    let directories = Vec::<String>::deserialize(deserializer)?;
    if directories.is_empty() {
        return Err(serde::de::Error::custom("empty directories"));
    }
    Ok(directories)
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ManifestFile {
    pub file_name: String,
    pub size_bytes: String,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "optional_path"
    )]
    pub relative_path: Option<String>,
}
#[derive(Clone, Serialize, Deserialize)]
pub struct SelectedSource {
    pub source: PathBuf,
    pub file_name: String,
    #[serde(flatten)]
    pub stamp: SourceStamp,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "optional_path"
    )]
    pub relative_path: Option<String>,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SelectedDirectory {
    pub source: PathBuf,
    pub relative_path: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FolderSelection {
    pub sources: Vec<SelectedSource>,
    #[serde(default)]
    pub directories: Vec<SelectedDirectory>,
}
fn valid_path(path: &str) -> bool {
    !path.is_empty()
        && path.len() <= 512
        && path.split('/').count() <= 16
        && path.split('/').all(valid_name)
}
pub(crate) fn validate_manifest(files: &[ManifestFile], directories: &[String]) -> Result<()> {
    if files.len() > 64
        || directories.len() > 64
        || (directories.is_empty() && !(2..=64).contains(&files.len()))
    {
        return Err(Fault::new(400, "invalid_request"));
    }
    let mut names = HashSet::new();
    let dirs: HashSet<_> = directories.iter().map(String::as_str).collect();
    for path in directories {
        if !valid_path(path) || !names.insert(path.to_lowercase()) {
            return Err(Fault::new(400, "invalid_name"));
        }
        if let Some((parent, _)) = path.rsplit_once('/')
            && !dirs.contains(parent)
        {
            return Err(Fault::new(400, "invalid_request"));
        }
    }
    for file in files {
        if !valid_name(&file.file_name) {
            return Err(Fault::new(400, "invalid_name"));
        }
        parse_size(&file.size_bytes).ok_or(Fault::new(400, "invalid_request"))?;
        if let Some(path) = &file.relative_path {
            let Some((parent, name)) = path.rsplit_once('/') else {
                return Err(Fault::new(400, "invalid_request"));
            };
            if !valid_path(path) || name != file.file_name || !names.insert(path.to_lowercase()) {
                return Err(Fault::new(400, "invalid_name"));
            }
            if !dirs.contains(parent) {
                return Err(Fault::new(400, "invalid_request"));
            }
        } else if names.contains(&file.file_name.to_lowercase()) {
            return Err(Fault::new(400, "invalid_name"));
        }
    }
    Ok(())
}
pub(crate) fn no_links(path: &Path) -> Result<()> {
    let mut current = PathBuf::new();
    for component in path.components() {
        if matches!(component, std::path::Component::ParentDir) {
            return Err(Fault::new(400, "invalid_request"));
        }
        current.push(component);
        if matches!(component, std::path::Component::Prefix(_)) {
            continue;
        }
        let metadata = fs::symlink_metadata(&current).map_err(|e| io_fault(&e))?;
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            if metadata.file_attributes() & 0x400 != 0 {
                return Err(Fault::new(400, "invalid_request"));
            }
        }
        if metadata.file_type().is_symlink() {
            return Err(Fault::new(400, "invalid_request"));
        }
    }
    Ok(())
}
impl FolderSelection {
    fn manifest(&self) -> Vec<ManifestFile> {
        self.sources
            .iter()
            .map(|source| ManifestFile {
                file_name: source.file_name.clone(),
                size_bytes: source.stamp.size_bytes.clone(),
                relative_path: source.relative_path.clone(),
            })
            .collect()
    }
    pub fn validate(&self) -> Result<()> {
        let directories: Vec<_> = self
            .directories
            .iter()
            .map(|dir| dir.relative_path.clone())
            .collect();
        if directories.is_empty() {
            if self.sources.is_empty()
                || self.sources.len() > 64
                || self.sources.iter().any(|s| s.relative_path.is_some())
            {
                return Err(Fault::new(400, "invalid_request"));
            }
        } else {
            validate_manifest(&self.manifest(), &directories)?;
        }
        for dir in &self.directories {
            no_links(&dir.source)?;
            if !dir.source.is_dir() {
                return Err(Fault::new(409, "source_changed"));
            }
        }
        for source in &self.sources {
            no_links(&source.source)?;
            let (name, inspected) = Engine::inspect_source(&source.source)?;
            if name != source.file_name || !source.stamp.matches(&inspected) {
                return Err(Fault::new(409, "source_changed"));
            }
        }
        Ok(())
    }
}
/// Expand once at selection time. Sending validates this exact selection without
/// rescanning and silently adding files the user never selected.
pub fn inspect_selection(paths: Vec<PathBuf>) -> Result<FolderSelection> {
    fn visit(
        path: PathBuf,
        relative: Option<String>,
        selection: &mut FolderSelection,
    ) -> Result<()> {
        no_links(&path)?;
        let metadata = fs::symlink_metadata(&path).map_err(|e| io_fault(&e))?;
        if metadata.is_dir() {
            let relative = relative.unwrap_or_else(|| {
                path.file_name()
                    .and_then(|s| s.to_str())
                    .unwrap_or("")
                    .to_owned()
            });
            if !valid_path(&relative) {
                return Err(Fault::new(400, "invalid_name"));
            }
            selection.directories.push(SelectedDirectory {
                source: path.clone(),
                relative_path: relative.clone(),
            });
            if selection.directories.len() > 64 {
                return Err(Fault::new(413, "limit_exceeded"));
            }
            let budget = (64 - selection.sources.len()) + (64 - selection.directories.len());
            let mut children = fs::read_dir(&path)
                .map_err(|e| io_fault(&e))?
                .take(budget + 1)
                .map(|entry| entry.map(|e| e.path()))
                .collect::<std::io::Result<Vec<_>>>()
                .map_err(|e| io_fault(&e))?;
            if children.len() > budget {
                return Err(Fault::new(413, "limit_exceeded"));
            }
            children.sort();
            for child in children {
                let name = child
                    .file_name()
                    .and_then(|n| n.to_str())
                    .ok_or(Fault::new(400, "invalid_name"))?;
                let child_relative = format!("{relative}/{name}");
                visit(child, Some(child_relative), selection)?;
            }
        } else if metadata.is_file() {
            let (file_name, inspected) = Engine::inspect_source(&path)?;
            selection.sources.push(SelectedSource {
                source: inspected.path,
                file_name,
                stamp: SourceStamp::new(inspected.len, inspected.modified),
                relative_path: relative,
            });
            if selection.sources.len() > 64 {
                return Err(Fault::new(413, "limit_exceeded"));
            }
        } else {
            return Err(Fault::new(400, "invalid_request"));
        }
        Ok(())
    }
    let mut selection = FolderSelection {
        sources: vec![],
        directories: vec![],
    };
    for path in paths {
        visit(path, None, &mut selection)?;
    }
    selection.validate()?;
    Ok(selection)
}
/// Each remote root gets a newly created local directory. Children can only use
/// directories made during this approval, never a pre-existing path by name.
pub(crate) fn prepare_directories(
    base: &Path,
    directories: &[String],
) -> Result<HashMap<String, PathBuf>> {
    let mut paths = HashMap::new();
    let mut ordered: Vec<_> = directories.iter().collect();
    ordered.sort_by_key(|path| path.split('/').count());
    let mut created = Vec::new();
    let result = (|| {
        for relative in ordered {
            let path = if let Some((parent, name)) = relative.rsplit_once('/') {
                let parent: &PathBuf = paths
                    .get(parent)
                    .ok_or(Fault::new(400, "invalid_request"))?;
                no_links(parent)?;
                let path = parent.join(name);
                fs::create_dir(&path).map_err(|e| io_fault(&e))?;
                path
            } else {
                let mut chosen = None;
                for suffix in 0..10_000 {
                    let name = if suffix == 0 {
                        relative.clone()
                    } else {
                        format!("{relative} ({suffix})")
                    };
                    let path = base.join(name);
                    match fs::create_dir(&path) {
                        Ok(()) => {
                            chosen = Some(path);
                            break;
                        }
                        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
                        Err(error) => return Err(io_fault(&error)),
                    }
                }
                chosen.ok_or(Fault::new(500, "io_error"))?
            };
            created.push(path.clone());
            no_links(&path)?;
            paths.insert(relative.clone(), path);
        }
        Ok(paths)
    })();
    if result.is_err() {
        for path in created.into_iter().rev() {
            let _ = fs::remove_dir(path);
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn path_depth_size_case_and_plain_compatibility_are_checked() {
        let directories: Vec<_> = (1..=16).map(|depth| vec!["p"; depth].join("/")).collect();
        validate_manifest(&[], &directories).unwrap();
        let mut too_deep = directories.clone();
        too_deep.push(vec!["p"; 17].join("/"));
        assert!(validate_manifest(&[], &too_deep).is_err());
        let too_long: Vec<_> = (1..=4)
            .map(|depth| vec!["p".repeat(130); depth].join("/"))
            .collect();
        assert!(validate_manifest(&[], &too_long).is_err());
        assert!(
            validate_manifest(
                &[],
                &(0..65).map(|n| format!("dir-{n}")).collect::<Vec<_>>()
            )
            .is_err()
        );
        assert!(validate_manifest(&[], &["Parent".into(), "parent/Child".into()]).is_err());
        let file = ManifestFile {
            file_name: "same".into(),
            size_bytes: "0".into(),
            relative_path: None,
        };
        validate_manifest(&[file.clone(), file.clone()], &[]).unwrap();
        assert!(validate_manifest(&[file], &[]).is_err());
    }
    #[test]
    fn preparation_failure_removes_only_its_new_empty_directories() {
        let destination = tempfile::tempdir().unwrap();
        fs::create_dir(destination.path().join("Keep")).unwrap();
        fs::write(destination.path().join("Keep/original"), b"keep").unwrap();
        assert!(
            prepare_directories(destination.path(), &["New".into(), "Missing/Child".into()])
                .is_err()
        );
        assert!(!destination.path().join("New").exists());
        assert_eq!(
            fs::read(destination.path().join("Keep/original")).unwrap(),
            b"keep"
        );
    }
    #[cfg(windows)]
    #[test]
    fn canonical_windows_prefixes_are_checked_after_their_root_component() {
        let directory = tempfile::tempdir().unwrap();
        no_links(&fs::canonicalize(directory.path()).unwrap()).unwrap();
    }
}
