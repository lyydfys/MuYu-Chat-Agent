/**
 * Fetch the latest GitHub release and write data/release.json.
 *
 * This runs at build time, never in the browser: the GitHub API is rate limited,
 * slow from some networks, and would leak visitor referrers.
 *
 * Usage:
 *   node scripts/fetch-release.mjs            # write data/release.json
 *   node scripts/fetch-release.mjs --check    # fail if the file is stale
 *   GITHUB_TOKEN=... node scripts/fetch-release.mjs
 */

import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = resolve(ROOT, 'data/release.json');
const REPO = 'lyydfys/MuYu-Chat-Agent';
const CHECK_ONLY = process.argv.includes('--check');

const MB = (bytes) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;

function trimNotes() {
  return 'See the release notes on GitHub for verification and known limitations.';
}

async function main() {
  const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'mca-website-build' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;

  const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, { headers });
  if (!res.ok) {
    console.error(`GitHub API ${res.status} ${res.statusText}`);
    process.exit(1);
  }
  const body = await res.json();

  const apk = (body.assets || []).find((a) => /\.apk$/i.test(a.name));
  if (!apk) {
    console.error('latest release has no APK asset; keeping the previous data file');
    process.exit(1);
  }

  const sha256 = (apk.digest || '').replace(/^sha256:/, '') || null;
  if (!sha256) console.warn('warning: GitHub did not return a digest for the APK; sha256 stays null');

  const data = {
    schema: 'mca.website.release.v1',
    generated_at: new Date().toISOString(),
    source: `https://api.github.com/repos/${REPO}/releases/latest`,
    tag: body.tag_name,
    name: body.name,
    published_at: body.published_at,
    published_date: (body.published_at || '').slice(0, 10),
    asset_name: apk.name,
    size_bytes: apk.size,
    size_label: MB(apk.size),
    sha256,
    abi: 'arm64-v8a',
    min_sdk: 26,
    min_android: 'Android 8.0',
    license: 'Apache-2.0',
    download_url: apk.browser_download_url,
    releases_url: `https://github.com/${REPO}/releases`,
    latest_url: `https://github.com/${REPO}/releases/latest`,
    mirrors: [
      {
        label: 'gh-proxy.com',
        url: `https://gh-proxy.com/${apk.browser_download_url}`,
        note: '第三方加速，与应用内更新器一致；安装前核对 SHA-256',
      },
    ],
    notes_hint: trimNotes(),
  };

  if (CHECK_ONLY) {
    let current = null;
    try {
      current = JSON.parse(await readFile(OUT, 'utf8'));
    } catch (err) {
      console.error('data/release.json is missing or unreadable');
      process.exit(1);
    }
    if (current.tag !== data.tag || current.sha256 !== data.sha256) {
      console.error(`stale release data: file has ${current.tag}, latest is ${data.tag}`);
      process.exit(1);
    }
    console.log(`release data is current (${data.tag})`);
    return;
  }

  await writeFile(OUT, `${JSON.stringify(data, null, 2)}\n`, 'utf8');
  console.log(`wrote data/release.json for ${data.tag} (${data.size_label}, sha256 ${data.sha256 ? data.sha256.slice(0, 12) : 'unknown'})`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
