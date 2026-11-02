#!/usr/bin/env python3
"""Compare two raw float32 latent tensors (little-endian, channel-major).

The chain smoke writes `final-latent.f32` (16 x 64 x 64 for 512x512) next to
`report.json`. Use this to compare that tensor with a CPU/reference latent of
the same shape. It reports difference statistics and correlation; it does not
decide perceptual quality.

Exit codes: 0 = shapes match and finite; 1 = shape/finite failure or a supplied
--max-abs-diff tolerance was exceeded; 2 = usage/IO error.
"""
from __future__ import annotations

import argparse
import json
import math
import pathlib
import struct
import sys


def parse_shape(value: str) -> tuple[int, int, int]:
    parts = value.lower().split('x')
    if len(parts) != 3:
        raise argparse.ArgumentTypeError('shape must look like 16x64x64')
    try:
        channels, height, width = (int(part) for part in parts)
    except ValueError as error:
        raise argparse.ArgumentTypeError(str(error)) from error
    if min(channels, height, width) <= 0:
        raise argparse.ArgumentTypeError('shape dimensions must be positive')
    return channels, height, width


def read_latent(path: pathlib.Path, shape: tuple[int, int, int]) -> list[float]:
    data = path.read_bytes()
    count = shape[0] * shape[1] * shape[2]
    expected = count * 4
    if len(data) != expected:
        raise ValueError(f'{path}: {len(data)} bytes, expected {expected} for {shape[0]}x{shape[1]}x{shape[2]}')
    return list(struct.unpack_from(f'<{count}f', data, 0))


def summarize(values: list[float]) -> dict:
    finite = [value for value in values if math.isfinite(value)]
    if not finite:
        return {'count': len(values), 'finite': 0}
    mean = sum(finite) / len(finite)
    squares = sum(value * value for value in finite)
    variance = max(0.0, squares / len(finite) - mean * mean)
    absolute = sum(abs(value) for value in finite)
    return {
        'count': len(values),
        'finite': len(finite),
        'min': min(finite),
        'max': max(finite),
        'mean': mean,
        'std': math.sqrt(variance),
        'mean_abs': absolute / len(finite),
    }


def compare(left: list[float], right: list[float]) -> dict:
    count = len(left)
    finite_pairs = [(a, b) for a, b in zip(left, right) if math.isfinite(a) and math.isfinite(b)]
    if not finite_pairs:
        return {'count': count, 'finite_pairs': 0}
    differences = [abs(a - b) for a, b in finite_pairs]
    rmse = math.sqrt(sum(diff * diff for diff in differences) / len(differences))
    dot = sum(a * b for a, b in finite_pairs)
    left_norm = math.sqrt(sum(a * a for a, _ in finite_pairs))
    right_norm = math.sqrt(sum(b * b for _, b in finite_pairs))
    cosine = dot / (left_norm * right_norm) if left_norm and right_norm else 0.0
    left_mean = sum(a for a, _ in finite_pairs) / len(finite_pairs)
    right_mean = sum(b for _, b in finite_pairs) / len(finite_pairs)
    covariance = sum((a - left_mean) * (b - right_mean) for a, b in finite_pairs)
    left_var = sum((a - left_mean) ** 2 for a, _ in finite_pairs)
    right_var = sum((b - right_mean) ** 2 for _, b in finite_pairs)
    correlation = covariance / math.sqrt(left_var * right_var) if left_var and right_var else 0.0
    baseline = sum(abs(value) for value in left) / len(left)
    return {
        'count': count,
        'finite_pairs': len(finite_pairs),
        'max_abs_diff': max(differences),
        'mean_abs_diff': sum(differences) / len(differences),
        'rmse': rmse,
        'relative_l1': (sum(differences) / len(differences)) / baseline if baseline else 0.0,
        'cosine': cosine,
        'pearson': correlation,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('reference', type=pathlib.Path)
    parser.add_argument('candidate', type=pathlib.Path)
    parser.add_argument('--shape', type=parse_shape, default=(16, 64, 64))
    parser.add_argument('--max-abs-diff', type=float, default=None,
                        help='fail when max_abs_diff exceeds this value')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    try:
        left = read_latent(args.reference, args.shape)
        right = read_latent(args.candidate, args.shape)
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 2
    report = {
        'schema': 'mca.latent-compare/1',
        'shape': list(args.shape),
        'reference': {'path': str(args.reference), 'stats': summarize(left)},
        'candidate': {'path': str(args.candidate), 'stats': summarize(right)},
        'comparison': compare(left, right),
    }
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        stats = report['comparison']
        print(f"shape={args.shape[0]}x{args.shape[1]}x{args.shape[2]}")
        print(f"max_abs_diff={stats['max_abs_diff']:.6g} mean_abs_diff={stats['mean_abs_diff']:.6g} "
              f"relative_l1={stats['relative_l1']:.6g} rmse={stats['rmse']:.6g}")
        print(f"cosine={stats['cosine']:.6f} pearson={stats['pearson']:.6f}")
    if report['comparison']['finite_pairs'] != report['comparison']['count']:
        print('non-finite values present', file=sys.stderr)
        return 1
    if args.max_abs_diff is not None and report['comparison']['max_abs_diff'] > args.max_abs_diff:
        print(f"max_abs_diff {report['comparison']['max_abs_diff']} exceeds {args.max_abs_diff}", file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
