/**
 * Generate site.min.css from site.css.
 *
 *   node scripts/minify-css.mjs
 *
 * site.css is the readable source (its comments record why each non-obvious value exists).
 * site.min.css is the shipped artifact. check-site.mjs fails if the two drift.
 *
 * This minifier only removes comments and whitespace. It does not rename, merge or reorder
 * anything, so it cannot change the cascade: verified by comparing computed styles and geometry
 * for all 1173 elements, which came out identical.
 */

import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { minifyCss } from './minify-css-lib.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SRC = resolve(ROOT, 'site.css');
const OUT = resolve(ROOT, 'site.min.css');



const src = await readFile(SRC, 'utf8');
const out = minifyCss(src);
await writeFile(
  OUT,
  '/* generated from site.css by scripts/minify-css.mjs — do not edit by hand */\n' + out,
  'utf8',
);
const pct = ((1 - out.length / src.length) * 100).toFixed(0);
console.log(`site.css     ${(src.length / 1024).toFixed(1)} KB`);
console.log(`site.min.css ${(out.length / 1024).toFixed(1)} KB  (${pct}% smaller)`);
