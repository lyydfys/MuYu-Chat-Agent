#!/usr/bin/env python3
"""Compare two ACTX v1 contexts (float32, shape tokens x width).

Use this to compare MCA's `dynamic-actx.bin` with a reference ACTX captured from
the reference text path. It reports shape, finite counts, difference statistics
and correlation; it does not decide perceptual quality.

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


def read_actx(path: pathlib.Path) -> tuple[int, int, list[float]]:
    data = path.read_bytes()
    if len(data) < 16 or data[:4] != b'ACTX':
        raise ValueError(f'{path}: not an ACTX container')
    version, tokens, width = struct.unpack_from('<III', data, 4)
    if version != 1:
        raise ValueError(f'{path}: unsupported ACTX version {version}')
    expected = tokens * width * 4
    if len(data) - 16 != expected:
        raise ValueError(f'{path}: {len(data) - 16} payload bytes, expected {expected}')
    values = list(struct.unpack_from(f'<{tokens * width}f', data, 16))
    return tokens, width, values


def summarize(values: list[float]) -> dict:
    finite = [value for value in values if math.isfinite(value)]
    if not finite:
        return {'count': len(values), 'finite': 0}
    mean = sum(finite) / len(finite)
    squares = sum(value * value for value in finite)
    variance = max(0.0, squares / len(finite) - mean * mean)
    return {
        'count': len(values),
        'finite': len(finite),
        'min': min(finite),
        'max': max(finite),
        'mean': mean,
        'std': math.sqrt(variance),
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
    return {
        'count': count,
        'finite_pairs': len(finite_pairs),
        'max_abs_diff': max(differences),
        'mean_abs_diff': sum(differences) / len(differences),
        'rmse': rmse,
        'cosine': cosine,
        'pearson': correlation,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('reference', type=pathlib.Path)
    parser.add_argument('candidate', type=pathlib.Path)
    parser.add_argument('--max-abs-diff', type=float, default=None,
                        help='fail when max_abs_diff exceeds this value')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    try:
        ref_tokens, ref_width, ref_values = read_actx(args.reference)
        cand_tokens, cand_width, cand_values = read_actx(args.candidate)
    except (OSError, ValueError) as error:
        print(str(error), file=sys.stderr)
        return 2
    shape_ok = (ref_tokens, ref_width) == (cand_tokens, cand_width)
    report = {
        'schema': 'mca.actx-compare/1',
        'reference': {'path': str(args.reference), 'tokens': ref_tokens, 'width': ref_width,
                      'stats': summarize(ref_values)},
        'candidate': {'path': str(args.candidate), 'tokens': cand_tokens, 'width': cand_width,
                      'stats': summarize(cand_values)},
        'shapeMatches': shape_ok,
    }
    if shape_ok:
        report['comparison'] = compare(ref_values, cand_values)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"reference {ref_tokens}x{ref_width}  candidate {cand_tokens}x{cand_width}  shape={shape_ok}")
        if shape_ok:
            stats = report['comparison']
            print(f"max_abs_diff={stats['max_abs_diff']:.6g} mean_abs_diff={stats['mean_abs_diff']:.6g} "
                  f"rmse={stats['rmse']:.6g} cosine={stats['cosine']:.6f} pearson={stats['pearson']:.6f}")
    if not shape_ok:
        return 1
    if report['comparison']['finite_pairs'] != report['comparison']['count']:
        print('non-finite values present', file=sys.stderr)
        return 1
    if args.max_abs_diff is not None and report['comparison']['max_abs_diff'] > args.max_abs_diff:
        print(f"max_abs_diff {report['comparison']['max_abs_diff']} exceeds {args.max_abs_diff}", file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
