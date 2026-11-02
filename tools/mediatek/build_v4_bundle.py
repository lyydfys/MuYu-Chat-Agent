#!/usr/bin/env python3
"""Build an importable mca.image_engine.bundle.v1 for the four-chunk NPUG/AOT chain.

Inputs are the pinned four-chunk manifest produced by build_chain_kit.py and the
locked local assets. Every asset is re-hashed against the manifest before it is
copied. The output is a directory plus a ZIP the production app can import.

The bundle uses FIXED_CONTEXT (the prompt is accepted but declared ineffective)
and contains the four DLAs, four stubs, four routes, ctx, AMOD, RoPE and VAE.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import sys
import zipfile

REPO = pathlib.Path(__file__).resolve().parents[2]
EVIDENCE = REPO / 'artifacts' / 'architecture' / '2026-10-31-mtkpaint-rea'


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for block in iter(lambda: handle.read(1 << 22), b''):
            digest.update(block)
    return digest.hexdigest()


def collect_assets(node, found: dict[str, dict]) -> None:
    if isinstance(node, dict):
        if {'file', 'sha256', 'sizeBytes'} <= set(node):
            found.setdefault(node['file'], node)
        for value in node.values():
            collect_assets(value, found)
    elif isinstance(node, list):
        for value in node:
            collect_assets(value, found)


def source_for(rel: str, assets: pathlib.Path, routes: pathlib.Path, dynamic_text: bool) -> pathlib.Path:
    name = pathlib.Path(rel).name
    if rel.endswith('.route.json'):
        return routes / name
    if dynamic_text and name in ('text.bin', 'tok.bin'):
        return assets / name
    return assets / name


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', type=pathlib.Path, required=True)
    parser.add_argument('--assets', type=pathlib.Path, default=pathlib.Path('E:/mtk-npu-reference-assets'))
    parser.add_argument('--routes', type=pathlib.Path, default=EVIDENCE)
    parser.add_argument('--out', type=pathlib.Path, required=True)
    parser.add_argument('--zip', type=pathlib.Path, required=True)
    parser.add_argument('--dynamic-text', action='store_true')
    parser.add_argument('--force', action='store_true')
    args = parser.parse_args()

    manifest_text = args.manifest.read_text(encoding='utf-8')
    manifest = json.loads(manifest_text)
    found: dict[str, dict] = {}
    collect_assets(manifest, found)
    if not found:
        print('manifest pins no assets', file=sys.stderr)
        return 1

    out = args.out.resolve()
    if out.exists():
        if not args.force:
            print(f'{out} already exists; pass --force to replace', file=sys.stderr)
            return 1
        shutil.rmtree(out)
    out.mkdir(parents=True)

    for rel, pin in sorted(found.items()):
        source = source_for(rel, args.assets, args.routes, args.dynamic_text)
        if not source.is_file():
            print(f'missing asset {rel}: {source}', file=sys.stderr)
            return 1
        size = source.stat().st_size
        if size != pin['sizeBytes']:
            print(f'{rel}: {size} bytes, manifest pins {pin["sizeBytes"]}', file=sys.stderr)
            return 1
        digest = sha256(source)
        if digest != pin['sha256']:
            print(f'{rel}: sha {digest}, manifest pins {pin["sha256"]}', file=sys.stderr)
            return 1
        target = out / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        print(f'copied {rel} ({size} bytes)')

    aot_path = out / 'engine' / 'aot.json'
    aot_path.parent.mkdir(parents=True, exist_ok=True)
    aot_path.write_text(manifest_text, encoding='utf-8')

    components = [{
        'role': 'CONFIG',
        'path': 'engine/aot.json',
        'required': True,
        'expectedSizeBytes': aot_path.stat().st_size,
        'sha256': sha256(aot_path),
    }]
    for rel, pin in sorted(found.items()):
        components.append({
            'role': 'MODEL',
            'path': rel,
            'required': True,
            'expectedSizeBytes': pin['sizeBytes'],
            'sha256': pin['sha256'],
        })
    primary = next((rel for rel in sorted(found) if rel.endswith('.npug')), sorted(found)[0])
    envelope = {
        'schema': 'mca.image_engine.bundle.v1',
        'id': 'mediatek-reference-anima-512-fixed',
        'recommendationId': 'mediatek_anima_npu_np9',
        'title': 'MediaTek reference Anima 512 (fixed ACTX)',
        'runtime': 'MEDIATEK_NEURON',
        'family': 'ANIMA',
        'task': 'TEXT_TO_IMAGE',
        'imageSize': '512x512',
        'primary': primary,
        'mediatekAnimaManifestPath': 'engine/aot.json',
        'components': components,
        'smoke': {'width': 512, 'height': 512, 'steps': 6, 'timeoutSeconds': 120},
    }
    (out / 'manifest.json').write_text(json.dumps(envelope, indent=2) + '\n', encoding='utf-8')

    zip_path = args.zip.resolve()
    zip_path.parent.mkdir(parents=True, exist_ok=True)
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        for path in sorted(out.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(out).as_posix())
    digest = sha256(zip_path)
    print(json.dumps({
        'bundleDir': str(out),
        'zip': str(zip_path),
        'zipBytes': zip_path.stat().st_size,
        'zipSHA256': digest,
        'components': len(components),
        'conditioning': manifest.get('conditioning', {}).get('mode'),
        'profileId': manifest.get('profiles', [{}])[0].get('id'),
    }, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
