#!/usr/bin/env python3
"""Validate a device chain report and its pulled tensors.

This is the acceptance gate for evidence returned by `run_neuron_smoke.py`.
It does not judge image quality; it proves the report is complete, internally
consistent, and backed by the expected `final-latent.f32` (and ACTX, when the
run used dynamic text).

Exit codes: 0 = all required evidence present and consistent; 1 = a required
field/file/hash is missing or inconsistent; 2 = usage/IO error.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import struct
import sys

LATENT_CHANNELS = 16
LATENT_HEIGHT = 64
LATENT_WIDTH = 64


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for block in iter(lambda: handle.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def require(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('report', type=pathlib.Path)
    parser.add_argument('--kit-dir', type=pathlib.Path, default=None,
                        help='directory holding final-latent.f32 / dynamic-actx.bin (default: report parent)')
    args = parser.parse_args()
    kit_dir = args.kit_dir or args.report.parent
    failures: list[str] = []
    try:
        report = json.loads(args.report.read_text(encoding='utf-8'))
    except (OSError, ValueError) as error:
        print(f'cannot read report: {error}', file=sys.stderr)
        return 2

    require(report.get('status') == 'COMPLETED', f"status is {report.get('status')!r}", failures)
    baseline = report.get('baseline')
    require(isinstance(baseline, dict), 'baseline object is missing', failures)
    baseline = baseline if isinstance(baseline, dict) else {}
    for key in ('schema', 'modelIdentity', 'manifestSHA256', 'profileId', 'plannedSteps',
                'runSteps', 'latentSHA256', 'chunkSHA256', 'outputSHA256',
                'finalLatentFile', 'finalLatentElements', 'finalLatentSHA256',
                'finalLatentMin', 'finalLatentMax', 'finalLatentMean', 'finalLatentStd',
                'qualityStatus', 'deviceVerification'):
        require(key in baseline, f'baseline.{key} is missing', failures)
    require(baseline.get('schema') == 'mca.neuron-chain-baseline/1',
            f"baseline.schema is {baseline.get('schema')!r}", failures)

    chunks = report.get('chunks')
    require(isinstance(chunks, list) and len(chunks) > 0, 'chunks list is empty', failures)
    if isinstance(chunks, list):
        require(len(baseline.get('chunkSHA256') or []) == len(chunks),
                'baseline.chunkSHA256 length does not match chunks', failures)
        for index, chunk in enumerate(chunks):
            digest = chunk.get('sha256') if isinstance(chunk, dict) else None
            require(isinstance(digest, str) and len(digest) == 64,
                    f'chunks[{index}].sha256 is not a SHA-256', failures)
            for key in ('min', 'max', 'mean', 'std'):
                require(isinstance(chunk, dict) and key in chunk,
                        f'chunks[{index}].{key} is missing', failures)

    output = report.get('output') or {}
    require(output.get('pngSHA256') == baseline.get('outputSHA256'),
            'output.pngSHA256 does not match baseline.outputSHA256', failures)
    require(isinstance(output.get('pngBytes'), int) and output['pngBytes'] > 1024,
            'output.pngBytes is missing or too small', failures)

    latent_name = baseline.get('finalLatentFile')
    if isinstance(latent_name, str) and latent_name:
        latent_path = kit_dir / latent_name
        require(latent_path.is_file(), f'{latent_name} is missing next to the report', failures)
        if latent_path.is_file():
            expected = LATENT_CHANNELS * LATENT_HEIGHT * LATENT_WIDTH * 4
            require(latent_path.stat().st_size == expected,
                    f'{latent_name} is {latent_path.stat().st_size} bytes, expected {expected}', failures)
            require(sha256(latent_path) == baseline.get('finalLatentSHA256'),
                    f'{latent_name} SHA-256 does not match baseline.finalLatentSHA256', failures)
            require(baseline.get('finalLatentElements') == LATENT_CHANNELS * LATENT_HEIGHT * LATENT_WIDTH,
                    'baseline.finalLatentElements is not 16*64*64', failures)
            values = struct.unpack(f'<{LATENT_CHANNELS * LATENT_HEIGHT * LATENT_WIDTH}f',
                                   latent_path.read_bytes())
            require(all(value == value and abs(value) != float('inf') for value in values),
                    f'{latent_name} contains non-finite values', failures)

    text = report.get('textEncoding') or {}
    if isinstance(text, dict) and text.get('actxFile'):
        actx_path = kit_dir / text['actxFile']
        require(actx_path.is_file(), f"{text['actxFile']} is missing next to the report", failures)
        if actx_path.is_file():
            tokens = text.get('lk')
            expected = 16 + int(tokens) * 1024 * 4 if isinstance(tokens, int) else -1
            require(actx_path.stat().st_size == expected,
                    f"{text['actxFile']} is {actx_path.stat().st_size} bytes, expected {expected}", failures)
            require(sha256(actx_path) == text.get('actxSHA256'),
                    f"{text['actxFile']} SHA-256 does not match textEncoding.actxSHA256", failures)

    if failures:
        print('CHAIN_REPORT_INVALID')
        for failure in failures:
            print(f'  - {failure}')
        return 1
    print('CHAIN_REPORT_VALID')
    print(f"  chunks={len(chunks) if isinstance(chunks, list) else 0} "
          f"latent={baseline.get('finalLatentSHA256')} output={baseline.get('outputSHA256')}")
    print('  quality remains NOT_VERIFIED_NO_REFERENCE_BASELINE; this gate only validates evidence completeness.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
