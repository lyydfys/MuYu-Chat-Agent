// Prunes an installed DSH node_modules tree down to what the Android payload needs.
//
// The first attempt of this upgrade deleted any directory named `doc`/`docs`, which removed
// `yaml/dist/doc/` — real code, not documentation — and the kernel died on the phone with
// MODULE_NOT_FOUND. The rules below are deliberately narrow: only files that cannot execute
// (source maps, type declarations, markdown that is not a licence) and package-root test/example
// trees are removed. Nested `doc/`, `test/`, `src/` directories are left alone.
import fs from "node:fs";
import path from "node:path";

const root = process.argv[2];
if (!root) {
  console.error("usage: node prune-payload.mjs <node_modules dir>");
  process.exit(2);
}
const KEEP_JSON = /^(LICENSE|LICENCE|NOTICE|COPYING)/i;
const PRUNE_FILE = /\.(map|d\.ts|d\.mts|d\.cts)$/;
const PRUNE_MD = /\.(md|markdown)$/i;

let removedBytes = 0;
let removedFiles = 0;
let removedDirs = 0;

function walk(dir, depth) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      const isPackageRoot = path.dirname(dir).endsWith("node_modules")
        && path.basename(dir).startsWith("@") === false;
      // Only a directory directly inside a package (node_modules/<pkg>/test) is a development
      // artifact. Anything nested deeper may be code.
      const packageDir = path.dirname(dir);
      const directlyInPackage = path.dirname(packageDir).endsWith("node_modules");
      if (directlyInPackage && /^(test|tests|__tests__|examples?)$/.test(entry.name)) {
        removedBytes += dirSize(full);
        removedFiles += countFiles(full);
        fs.rmSync(full, { recursive: true, force: true });
        removedDirs += 1;
        continue;
      }
      walk(full, depth + 1);
      continue;
    }
    if (!entry.isFile()) continue;
    if (KEEP_JSON.test(entry.name)) continue;
    if (PRUNE_FILE.test(entry.name) || PRUNE_MD.test(entry.name)) {
      removedBytes += fs.statSync(full).size;
      removedFiles += 1;
      fs.rmSync(full, { force: true });
    }
  }
}

function dirSize(dir) {
  let total = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    total += entry.isDirectory() ? dirSize(full) : fs.statSync(full).size;
  }
  return total;
}

function countFiles(dir) {
  let total = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    total += entry.isDirectory() ? countFiles(full) : 1;
  }
  return total;
}

walk(root, 0);
console.log("removed files:", removedFiles, "dirs:", removedDirs,
  "megabytes:", (removedBytes / 1024 / 1024).toFixed(1));
