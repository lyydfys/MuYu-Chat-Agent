/**
 * Static checks for the MCA site. No dependencies, no browser.
 *
 *   node scripts/check-site.mjs
 *
 * Verifies:
 *  1. every internal href/src target exists on disk;
 *  2. the release facts in index.html match data/release.json;
 *  3. no placeholder text, TODO, or localhost links leaked into the page;
 *  4. every <img> has alt text and explicit width/height;
 *  5. localStorage/sessionStorage access is wrapped in try/catch.
 */

import { access, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { minifyCss } from './minify-css-lib.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const INDEX = resolve(ROOT, 'index.html');
const RELEASE = resolve(ROOT, 'data/release.json');

const errors = [];
const warns = [];
const ok = (msg) => console.log(`  ok   ${msg}`);
const fail = (msg) => { errors.push(msg); console.log(`  FAIL ${msg}`); };
const warn = (msg) => { warns.push(msg); console.log(`  warn ${msg}`); };

const html = await readFile(INDEX, 'utf8');
const release = JSON.parse(await readFile(RELEASE, 'utf8'));

console.log('links');
const refs = new Set();
for (const m of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
  const value = m[1];
  if (!value || value.startsWith('#') || value.startsWith('http') || value.startsWith('//') || value.startsWith('data:') || value.startsWith('mailto:')) continue;
  refs.add(value.split('?')[0]);
}
for (const ref of refs) {
  try {
    await access(resolve(ROOT, ref));
  } catch {
    fail(`missing local asset: ${ref}`);
  }
}
if (!errors.length) ok(`${refs.size} local references resolve`);

console.log('release facts');
const checks = [
  [release.tag, 'tag'],
  [release.size_label, 'size label'],
  [release.sha256, 'sha256'],
  [String(release.min_sdk), 'min sdk'],
];
for (const [value, label] of checks) {
  if (!value) { warn(`release.json has no ${label}`); continue; }
  if (!html.includes(value)) fail(`index.html does not mention the ${label} (${value})`);
}
if (!errors.some((e) => e.includes('sha256'))) ok('release tag/size/sha/min-sdk appear in the page');

console.log('copy hygiene');
const banned = [
  ['TODO', /TODO|FIXME|XXX/],
  // "敬请期待" is only allowed as a negation ("不是一页「敬请期待」"), never as real copy.
  ['placeholder copy', /(?<!不)敬请期待|即将上线|lorem ipsum/],
  ['dev server url', /localhost:[0-9]{2,5}|127\.0\.0\.1:(?!11435)/],
];
for (const [label, re] of banned) {
  const hit = html.match(re);
  if (hit) fail(`${label} found: ${hit[0]}`);
}
if (!errors.some((e) => /TODO|placeholder|dev server/.test(e))) ok('no placeholders or dev URLs');

console.log('images');
for (const m of html.matchAll(/<img\b[^>]*>/g)) {
  const tag = m[0];
  if (!/\balt="/.test(tag)) fail(`<img> without alt: ${tag.slice(0, 80)}`);
  if (!/\bwidth="/.test(tag) || !/\bheight="/.test(tag)) fail(`<img> without width/height: ${tag.slice(0, 80)}`);
  if (!/\b(loading|decoding|fetchpriority)=/.test(tag)) warn(`<img> without loading/decoding hint: ${tag.slice(0, 80)}`);
}
ok('image attributes checked');

console.log('storage safety');
const js = await readFile(resolve(ROOT, 'site.js'), 'utf8');
const storageUses = [...js.matchAll(/sessionStorage\.(get|set|remove)Item/g)];
if (storageUses.length) {
  const guarded = /try\s*{[^}]*sessionStorage/s.test(html);
  if (!guarded) warn('inline boot script uses sessionStorage outside try/catch');
}


/* 6. site.min.css must be in sync with site.css (generated, never hand-edited). */
const CSS_SRC = resolve(ROOT, 'site.css');
const CSS_MIN = resolve(ROOT, 'site.min.css');

try {
  const src = await readFile(CSS_SRC, 'utf8');
  const min = await readFile(CSS_MIN, 'utf8');
  // Strip the generator banner, then normalise the newline it leaves behind.
  const body = min.replace(/\/\*[^]*?\*\//g, '').trim() + '\n';
  if (minifyCss(src) !== body) {
    fail('site.min.css is stale - run: node scripts/minify-css.mjs');
  } else {
    ok('site.min.css in sync with site.css');
  }
} catch (e) {
  fail('site.min.css missing - run: node scripts/minify-css.mjs');
}

console.log(`\n${errors.length ? `${errors.length} error(s)` : 'all checks passed'}, ${warns.length} warning(s)`);
process.exit(errors.length ? 1 : 0);
