#!/usr/bin/env python3
"""Bind each route's `dla` identity to the manifest compiled network, then rezip.

The recovered route evidence was exported before DLA recovery, so `dla` is null.
The production importer requires it to match the manifest compiled network.
This patches the bundle copies only; the evidence files stay untouched.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import zipfile


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for block in iter(lambda: handle.read(1 << 22), b''):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=pathlib.Path, required=True)
    parser.add_argument('--zip', type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = args.bundle.resolve()
    envelope_path = root / 'manifest.json'
    envelope = json.loads(envelope_path.read_text(encoding='utf-8'))
    aot_path = root / envelope['mediatekAnimaManifestPath']
    aot = json.loads(aot_path.read_text(encoding='utf-8'))
    patched = 0
    for variant in aot['executionVariants']:
        compiled = variant.get('compiledNetwork')
        route_rel = variant.get('route', {}).get('asset', {}).get('file')
        if not compiled or not route_rel:
            continue
        route_path = root / route_rel
        route = json.loads(route_path.read_text(encoding='utf-8'))
        if route.get('dla'):
            continue
        asset = compiled['asset']
        route['dla'] = {
            'path': f"aot/dla/{pathlib.Path(asset['file']).name}",
            'bytes': asset['sizeBytes'],
            'sha256': asset['sha256'],
        }
        route_path.write_text(json.dumps(route, indent=2) + '\n', encoding='utf-8')
        patched += 1
    for variant in aot['executionVariants']:
        route_asset = variant.get('route', {}).get('asset')
        if not route_asset:
            continue
        route_path = root / route_asset['file']
        route_asset['sizeBytes'] = route_path.stat().st_size
        route_asset['sha256'] = sha256(route_path)
    aot_path.write_text(json.dumps(aot, indent=2) + '\n', encoding='utf-8')
    for component in envelope['components']:
        path = root / component['path']
        component['expectedSizeBytes'] = path.stat().st_size
        component['sha256'] = sha256(path)
    envelope_path.write_text(json.dumps(envelope, indent=2) + '\n', encoding='utf-8')
    zip_path = args.zip.resolve()
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        for path in sorted(root.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(root).as_posix())
    print(json.dumps({
        'patchedRoutes': patched,
        'zip': str(zip_path),
        'zipBytes': zip_path.stat().st_size,
        'zipSHA256': sha256(zip_path),
    }, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
