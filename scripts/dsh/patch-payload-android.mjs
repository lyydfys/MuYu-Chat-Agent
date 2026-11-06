// Android compatibility patches for the official DSH payload.
//
// The official npm closure assumes a desktop/server host: `node-addon-require-builtin` resolves a
// per-platform Node-API addon (`node-addon-require-builtin-<platform>-<arch>`), and no Android
// variant is published. `dsh-app-boot` requires it unconditionally during host preparation, so the
// kernel dies at startup on a phone:
//
//   Cannot find module '.../napi-v9-android-arm64/require_builtin.node'
//   dsh: FAILED NODE_START_FAILED (code 1)
//
// MCA launches its bundled Node with `--expose-internals` (libdshnode.so), where the same internal
// module identifiers resolve through a plain `require`. The patch keeps the native binding when it
// is available and falls back to that path otherwise, so desktop behaviour is unchanged.
//
// Every patch is idempotent and refuses to run against a file it does not recognise, so a future
// kernel upgrade fails loudly here instead of silently shipping an unpatched payload.
import fs from "node:fs";
import path from "node:path";

const nodeModules = process.argv[2];
if (!nodeModules) {
  console.error("usage: node patch-payload-android.mjs <payload>/dshroot/lib/node_modules");
  process.exit(2);
}

const PATCHED = `"use strict";
/* Android fallback applied by scripts/dsh/patch-payload-android.mjs.
 *
 * Upstream resolves a Node-API addon through optional platform packages. No Android build is
 * published, and dsh-app-boot's internalModules() has no try/catch, so a missing addon is fatal.
 * MCA starts Node with --expose-internals, where the same ids resolve via require(); the native
 * binding is still preferred when present.
 */
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;
const node_path_1 = __importDefault(require("node:path"));
const node_module_1 = require("node:module");
const requireInternal = (0, node_module_1.createRequire)(__filename);
let api = null;
let nativeFailure = null;
try {
    const { createEntryApi } = require('node-addon-native-custom-loader');
    api = createEntryApi(node_path_1.default.resolve(__dirname, '..'));
}
catch (error) {
    nativeFailure = error;
}
function requireBuiltin(moduleId) {
    if (api)
        return api.requireBuiltin(moduleId);
    return requireInternal(moduleId);
}
function isAllowedInternalId(moduleId) {
    if (api)
        return api.isAllowedInternalId(moduleId);
    // Under --expose-internals the set of reachable ids is decided by Node itself.
    return true;
}
function getBindingInfo() {
    if (api)
        return api.getBindingInfo();
    return {
        binding: 'js-fallback',
        reason: nativeFailure ? String(nativeFailure && nativeFailure.message || nativeFailure) : 'native binding unavailable',
    };
}
exports.default = {
    requireBuiltin,
    isAllowedInternalId,
    getBindingInfo,
};
`;

const target = path.join(nodeModules, "node-addon-require-builtin", "lib", "index.js");
const original = fs.readFileSync(target, "utf8");

if (original.includes("Android fallback applied by scripts/dsh/patch-payload-android.mjs")) {
  console.log("already patched:", target);
  process.exit(0);
}
if (!original.includes("const { createEntryApi } = require('node-addon-native-custom-loader');")
  || !original.includes("exports.getBindingInfo = getBindingInfo;")) {
  console.error("unexpected node-addon-require-builtin shape; refusing to patch:");
  console.error(target);
  process.exit(1);
}
const version = JSON.parse(
  fs.readFileSync(path.join(nodeModules, "node-addon-require-builtin", "package.json"), "utf8")
).version;
fs.writeFileSync(target, PATCHED);
console.log("patched node-addon-require-builtin@" + version + " with the Android fallback");
