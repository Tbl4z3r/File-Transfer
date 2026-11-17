#!/usr/bin/env python3
"""Inspecciona muestras AAR oficiales; no resuelve el grafo Gradle ni instala nada."""
import argparse
import hashlib
import io
import json
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

GOOGLE = "https://dl.google.com/dl/android/maven2/"
CENTRAL = "https://repo.maven.apache.org/maven2/"
SAMPLES = [
    (CENTRAL, "com.squareup.okhttp3", "okhttp-android", "5.4.0"),
    (GOOGLE, "androidx.startup", "startup-runtime", "1.2.0"),
    (GOOGLE, "androidx.tracing", "tracing", "1.0.0"),
]
COMPOSE = [
    (GOOGLE, "androidx.compose.ui", "ui-android", "1.9.4"),
    (GOOGLE, "androidx.compose.ui", "ui-graphics-android", "1.9.4"),
    (GOOGLE, "androidx.compose.runtime", "runtime-android", "1.9.4"),
    (GOOGLE, "androidx.compose.foundation", "foundation-android", "1.9.4"),
    (GOOGLE, "androidx.compose.material3", "material3-android", "1.4.0"),
    (GOOGLE, "androidx.graphics", "graphics-path", "1.0.1"),
    (GOOGLE, "androidx.activity", "activity-compose", "1.11.0"),
    (GOOGLE, "androidx.lifecycle", "lifecycle-runtime-android", "2.9.4"),
]


def inspect(coordinate):
    repository, group, artifact, version = coordinate
    url = f"{repository}{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.aar"
    with urllib.request.urlopen(url, timeout=30) as response:
        data = response.read(32 * 1024 * 1024 + 1)
    if len(data) > 32 * 1024 * 1024:
        raise ValueError(f"AAR supera el límite de 32 MiB: {url}")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        manifest = ET.fromstring(archive.read("AndroidManifest.xml"))
        uses_sdk = manifest.find("uses-sdk")
        minimum = None if uses_sdk is None else uses_sdk.get(
            "{http://schemas.android.com/apk/res/android}minSdkVersion"
        )
        metadata_path = "META-INF/com/android/build/gradle/aar-metadata.properties"
        metadata = (
            archive.read(metadata_path).decode("utf-8")
            if metadata_path in archive.namelist() else None
        )
        native = sorted(name for name in archive.namelist() if name.endswith(".so"))
    return {
        "coordinate": f"{group}:{artifact}:{version}",
        "url": url,
        "sha256": hashlib.sha256(data).hexdigest(),
        "minSdk": minimum,
        "aarMetadata": metadata,
        "nativeLibraries": native,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--include-compose", action="store_true")
    arguments = parser.parse_args()
    for sample in SAMPLES + (COMPOSE if arguments.include_compose else []):
        print(json.dumps(inspect(sample), ensure_ascii=False), flush=True)
