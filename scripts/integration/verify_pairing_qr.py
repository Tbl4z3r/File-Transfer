#!/usr/bin/env python3
"""Verify Windows QR pixels against the pinned decoder used by Android (no camera test)."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--windows-dir', type=Path, default=ROOT / 'apps/windows')
    parser.add_argument('--zxing-jar', type=Path)
    args = parser.parse_args()
    jar = args.zxing_jar
    if jar is None:
        cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
        matches = sorted((cache / 'caches/modules-2/files-2.1/com.google.zxing/core/3.5.4').glob('*/*.jar'))
        if not matches:
            parser.error('Build Android first, or supply --zxing-jar for core-3.5.4.jar')
        jar = matches[0]
    if not (args.windows_dir / 'dist/pairing-qr.js').is_file():
        parser.error('Run npm ci and npm run build in apps/windows first')
    with tempfile.TemporaryDirectory(prefix='ft-public-qr-fixture-') as folder:
        fixture = Path(folder) / 'pairing.gif'
        subprocess.run(['node', str(args.windows_dir.resolve() / 'scripts/export-pairing-qr.mjs'), str(fixture)], check=True)
        subprocess.run(['java', '-Xmx96m', '-Djava.awt.headless=true', '--class-path', str(jar.resolve()),
                        str(ROOT / 'scripts/integration/VerifyPairingQr.java'), str(fixture), str(fixture) + '.json'], check=True)


if __name__ == '__main__':
    main()
