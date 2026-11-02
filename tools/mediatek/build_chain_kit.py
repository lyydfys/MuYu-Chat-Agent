"""Build the four-chunk NPUG/AOT device kit (manifest, routes, latent, ctx, rope).

Inputs are the locked local assets under E:/mtk-npu-reference-assets and the
route evidence under artifacts/architecture/2026-10-31-mtkpaint-rea. The script
recomputes every referenced size/SHA-256 and refuses to write a kit when an
asset is missing or a hash differs from the route/lock evidence.

It does not run a device and does not copy the large DLA files; the runner
pushes them from the same asset root.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import shutil
import struct
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
EVIDENCE = REPO / 'artifacts' / 'architecture' / '2026-10-31-mtkpaint-rea'
TEMPLATE = REPO / 'core' / 'mediatek-native' / 'src' / 'test' / 'resources' / 'npug' / 'reference-aot-manifest.json'
DEFAULT_ASSETS = pathlib.Path('E:/mtk-npu-reference-assets')
LOCK = json.loads((EVIDENCE / 'reference-profile-lock.json').read_text(encoding='utf-8'))
LOCK_PINS = {
    entry['path']: entry
    for entry in LOCK['assets']
    if entry.get('match') and entry.get('declaredSHA256') and entry.get('declaredBytes') is not None
}
CHAIN = json.loads((EVIDENCE / 'chain-dataflow-evidence.json').read_text(encoding='utf-8'))
DLA_PINS = {
    entry['dla']['path']: entry['dla']
    for entry in CHAIN['chunks']
    if entry['dla'].get('match') and entry['dla'].get('localVerification') == 'FULL_RECOMPUTE'
}
CHUNKS = [
    ('chunk0', 'dva1_w8_w16L1024s4', 'dva1.route.json'),
    ('chunk1', 'dva2_w8_w16L1024s4', 'dva2_w8_w16L1024s4.route.json'),
    ('chunk2', 'dva3_w8_w16L1024s4', 'dva3_w8_w16L1024s4.route.json'),
    ('chunk3', 'ditc1c_w8_w16L1024s4', 'ditc1c.route.json'),
]
MASK64 = (1 << 64) - 1


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for block in iter(lambda: handle.read(1 << 22), b''):
            digest.update(block)
    return digest.hexdigest()


def asset(path: pathlib.Path, media_type: str) -> dict:
    if not path.is_file():
        raise SystemExit(f'missing asset: {path}')
    return {
        'file': path.name,
        'sha256': sha256(path),
        'sizeBytes': path.stat().st_size,
        'mediaType': media_type,
    }


def pinned(path: pathlib.Path, expected_sha: str, expected_bytes: int, label: str) -> pathlib.Path:
    if not path.is_file():
        raise SystemExit(f'{label}: missing {path}')
    actual_sha = sha256(path)
    actual_bytes = path.stat().st_size
    if actual_sha != expected_sha or actual_bytes != expected_bytes:
        raise SystemExit(
            f'{label}: {path.name} is {actual_bytes} B sha {actual_sha}; '
            f'route pins {expected_bytes} B sha {expected_sha}',
        )
    return path


class SplitMix64:
    def __init__(self, seed: int):
        self.state = seed & MASK64

    def next_u64(self) -> int:
        self.state = (self.state + 0x9E3779B97F4A7C15) & MASK64
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & MASK64
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & MASK64
        return z ^ (z >> 31)

    def next_unit(self) -> float:
        return (self.next_u64() >> 11) * (1.0 / (1 << 53))

    def next_normal(self) -> float:
        u1 = max(self.next_unit(), 1e-12)
        u2 = self.next_unit()
        return math.sqrt(-2.0 * math.log(u1)) * math.cos(2.0 * math.pi * u2)


def latent_bytes(seed: int, count: int) -> bytes:
    rng = SplitMix64(seed)
    raw = [rng.next_normal() for _ in range(count)]
    mean = sum(raw) / count
    variance = sum((value - mean) ** 2 for value in raw) / count
    scale = 1.0 / math.sqrt(variance) if variance > 0 else 1.0
    return struct.pack('<%df' % count, *((value - mean) * scale for value in raw))


def quantize_int16(value: float, scale: float) -> int:
    raw = int(math.floor(abs(value) / scale + 0.5))
    raw = raw if value >= 0 else -raw
    return max(-32768, min(32767, raw))


def ctx_int16(assets: pathlib.Path, scale: float) -> tuple[bytes, dict]:
    blob = (assets / 'ctx.bin').read_bytes()
    magic, version, tokens, width = struct.unpack_from('<4sIII', blob, 0)
    if (magic, version, tokens, width) != (b'ACTX', 1, 256, 1024):
        raise SystemExit('unexpected ACTX header')
    values = struct.unpack_from('<%df' % (tokens * width), blob, 16)
    quantized = [quantize_int16(value, scale) for value in values]
    return struct.pack('<%dh' % len(quantized), *quantized), {
        'tokens': tokens,
        'width': width,
        'scale': scale,
        'saturated': sum(1 for value in quantized if value in (-32768, 32767)),
    }


def lock_pin(path: str, label: str) -> tuple[str, int]:
    entry = LOCK_PINS.get(path)
    if entry is None:
        raise SystemExit(f'{label}: no verified lock pin for {path}')
    return entry['declaredSHA256'], entry['declaredBytes']


def build_variants(manifest: dict, routes: dict[str, dict], assets: pathlib.Path) -> list[dict]:
    variants = []
    for variant_id, graph, route_name in CHUNKS:
        route = routes[route_name]
        stub_path = route['stub']['path']
        dla_path = f'aot/mt6993/np9/{graph}.dla'
        stub_sha, stub_bytes = lock_pin(stub_path, f'{graph} stub')
        dla_entry = DLA_PINS.get(dla_path)
        if dla_entry is None:
            raise SystemExit(f'{graph} dla: no FULL_RECOMPUTE pin for {dla_path}')
        dla_sha, dla_bytes = dla_entry['sha256'], dla_entry['bytes']
        pinned(assets / pathlib.Path(stub_path).name, stub_sha, stub_bytes, f'{graph} stub')
        pinned(assets / pathlib.Path(dla_path).name, dla_sha, dla_bytes, f'{graph} dla')
        route_path = EVIDENCE / route_name
        route_asset = asset(route_path, 'application/json')
        route_asset['file'] = f'assets/npug/{route_name}'
        variants.append(
            {
                'id': variant_id,
                'transport': 'NEURON_COMPILED_NETWORK',
                'topology': {
                    'asset': {
                        'file': f'assets/npug/{pathlib.Path(stub_path).name}',
                        'sha256': stub_sha,
                        'sizeBytes': stub_bytes,
                        'mediaType': 'application/x-npug',
                    },
                    'containerMagic': 'NPUG',
                    'mode': 'STUB_TOPOLOGY',
                    'tensorCount': route['tensorCount'],
                    'operationCount': route['opCount'],
                    'weightDataBytes': 0,
                },
                'compiledNetwork': {
                    'asset': {
                        'file': f'assets/npug/{pathlib.Path(dla_path).name}',
                        'sha256': dla_sha,
                        'sizeBytes': dla_bytes,
                        'mediaType': 'application/x-neuron-dla',
                    },
                    'runtimeMajor': 9,
                    'graphId': graph,
                },
                'route': {
                    'asset': route_asset,
                    'graphId': graph,
                    'inputCount': route['inputCount'],
                    'outputCount': route['outputCount'],
                    'inputEncoding': {
                        'defaultEncoding': 'SCALED_SIGNED_INT16',
                        'overrides': {},
                        'rounding': 'HALF_AWAY_FROM_ZERO',
                        'saturates': True,
                    },
                },
                'jitFallbackAllowed': False,
            },
        )
    return variants


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--assets', type=pathlib.Path, default=DEFAULT_ASSETS)
    parser.add_argument('--out', type=pathlib.Path, required=True)
    parser.add_argument('--run-steps', type=int, default=1)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--dynamic-text', action='store_true')
    parser.add_argument('--prompt', default='a cat')
    args = parser.parse_args()

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(TEMPLATE.read_text(encoding='utf-8'))
    profile = manifest['profiles'][0]
    routes = {
        route_name: json.loads((EVIDENCE / route_name).read_text(encoding='utf-8'))
        for _, _, route_name in CHUNKS
    }
    if [group['shapeKey'] for group in routes['dva1.route.json']['inputShapeGroups']][0] != '[1,1024,68]':
        raise SystemExit('dva1 route no longer starts with [1,1024,68]')

    manifest['executionVariants'] = build_variants(manifest, routes, args.assets)

    modulation = args.assets / 'modsq16s4_1024_6.bin'
    rope = args.assets / 'rope_32x32.bin'
    vae = args.assets / 'vaedec_64.dla'
    modulation_asset = asset(modulation, 'application/octet-stream')
    modulation_asset['file'] = f'assets/npug/{modulation.name}'
    profile['modulations'][0]['asset'] = modulation_asset
    rope_asset = asset(rope, 'application/octet-stream')
    rope_asset['file'] = f'assets/npug/{rope.name}'
    profile['ropeAsset'] = rope_asset
    vae_asset = asset(vae, 'application/x-neuron-dla')
    vae_asset['file'] = f'assets/npug/{vae.name}'
    profile['vae']['asset'] = vae_asset
    if args.dynamic_text:
        weights_asset = asset(args.assets / 'text.bin', 'application/octet-stream')
        weights_asset['file'] = 'assets/npug/text.bin'
        tokenizer_asset = asset(args.assets / 'tok.bin', 'application/octet-stream')
        tokenizer_asset['file'] = 'assets/npug/tok.bin'
        manifest['conditioning'] = {
            'mode': 'DYNAMIC_TEXT',
            'promptEffective': True,
            'contextVersion': None,
            'contextAsset': None,
            'contextDtype': None,
            'contextShape': [],
            'textAssets': [weights_asset, tokenizer_asset],
        }
    manifest_path = out / 'chain-manifest.json'
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')

    for _, _, route_name in CHUNKS:
        shutil.copyfile(EVIDENCE / route_name, out / route_name)

    context_scale = routes['dva1.route.json']['inputs'][1]['quant']['scale']
    if args.dynamic_text:
        context_meta = None
    else:
        _, context_meta = ctx_int16(args.assets, context_scale)
        shutil.copyfile(args.assets / 'ctx.bin', out / 'ctx.bin')
    (out / 'zero.bin').write_bytes(b'\x00' * 512)
    rope_blob = rope.read_bytes()
    half = len(rope_blob) // 2
    (out / 'rope-low.bin').write_bytes(rope_blob[:half])
    (out / 'rope-high.bin').write_bytes(rope_blob[half:])
    latent = latent_bytes(args.seed, 16 * 64 * 64)
    (out / 'latent.f32').write_bytes(latent)

    config = {
        'schema': 'mca.neuron-chain-kit/1',
        'manifest': manifest_path.name,
        'profileId': profile['id'],
        'plannedSteps': 6,
        'runSteps': args.run_steps,
        'modulationFile': modulation.name,
        'conditioningMode': 'DYNAMIC_TEXT' if args.dynamic_text else 'FIXED_CONTEXT',
        'contextFile': None if args.dynamic_text else 'ctx.bin',
        'textWeightsFile': 'text.bin' if args.dynamic_text else None,
        'textTokenizerFile': 'tok.bin' if args.dynamic_text else None,
        'textPrompt': args.prompt if args.dynamic_text else None,
        'textLk': routes['dva1.route.json']['inputs'][1]['dims'][1] if args.dynamic_text else None,
        'zeroFile': 'zero.bin',
        'ropeLowFile': 'rope-low.bin',
        'ropeHighFile': 'rope-high.bin',
        'latentFile': 'latent.f32',
        'latentSHA256': hashlib.sha256(latent).hexdigest(),
        'latentSeed': args.seed,
        'context': context_meta,
        'chunks': [
            {
                'variantId': variant_id,
                'routeFile': route_name,
                'dlaFile': f'{graph}.dla',
            }
            for variant_id, graph, route_name in CHUNKS
        ],
        'vae': {'dlaFile': vae.name, 'outputBytes': 512 * 512 * 3 * 2},
        'note': (
            'The device smoke runs the production MediaTekNpugExecutor over this kit. '
            'ctx.bin is the raw ACTX asset for FIXED_CONTEXT; DYNAMIC_TEXT instead pushes '
            'text.bin/tok.bin and encodes the prompt on device. '
            'The runner pushes every DLA and the AMOD file from --assets.'
        ),
    }
    (out / 'chain-config.json').write_text(json.dumps(config, indent=2) + '\n', encoding='utf-8')

    print(json.dumps({
        'out': str(out),
        'manifest': str(manifest_path),
        'variants': [variant['id'] for variant in manifest['executionVariants']],
        'latentSeed': args.seed,
        'runSteps': args.run_steps,
        'contextSaturated': context_meta['saturated'] if context_meta else None,
        'files': sorted(path.name for path in out.iterdir()),
    }, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
