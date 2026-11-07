// Loads the four carried-over Android tool packages against the official 0.2.2-rc.1
// `@deepseek-ai/dsh-tools` contract and reports how many tools each one registers.
//
// This is the "capability preserved" probe: the packages are not published on npm, so the only way
// to know the kernel upgrade did not silently drop them is to run their `apply()` against the real
// 0.2.2 runtime objects and count `ctx.tools.register` calls.
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import path from "node:path";

const NM = process.argv[2];
if (!NM) {
  console.error("usage: node probe-android-tools.mjs <node_modules dir>");
  process.exit(2);
}
const require = createRequire(pathToFileURL(path.join(NM, "noop.js")));

const PACKAGES = [
  "dsh-tool-android",
  "dsh-tool-shizuku",
  "dsh-tool-accessibility",
  "dsh-tool-vscreen",
  // Carried over from the reference payload like the four above; the overlay seeds a
  // `tool-browser` row, so a missing package would silently drop the browser tools.
  "dsh-tool-browser",
];

function makeContext(registered) {
  const ctx = {
    tools: {
      register(definition, _options) {
        registered.push(definition?.name ?? definition?.id ?? "<unnamed>");
        return () => {};
      },
    },
    get() {
      return undefined;
    },
    on() {
      return () => {};
    },
    effect(fn) {
      return runCatching(fn);
    },
    logger: { info() {}, warn() {}, error() {}, debug() {} },
    log: { info() {}, warn() {}, error() {}, debug() {} },
    command() {
      return () => {};
    },
    plugin() {},
    // cordis: inject(deps, callback) runs the callback as soon as every dependency is present.
    // This stub has no service registry, so "present" is the right approximation.
    inject(_deps, callback) {
      const target = typeof callback === "function" ? callback : this;
      try {
        if (typeof callback === "function") callback(target === this ? ctx : target);
      } catch {
        // The real loader isolates a throwing injected callback; mirror that.
      }
      return () => {};
    },
    accept() {
      return () => {};
    },
    set() {},
  };
  ctx.inject = ctx.inject.bind(ctx);
  return ctx;
}

function runCatching(fn) {
  try {
    return fn?.();
  } catch (error) {
    return undefined;
  }
}

let failures = 0;
for (const name of PACKAGES) {
  const entry = path.join(NM, "@deepseek-ai", name, "lib", "index.js");
  const registered = [];
  try {
    const module = await import(pathToFileURL(entry).href);
    const apply = module.apply ?? module.default?.apply ?? module.default;
    if (typeof apply !== "function") {
      console.log(`${name}: no apply() export`);
      failures += 1;
      continue;
    }
    await apply(makeContext(registered));
    console.log(`${name}: registered ${registered.length} tool(s) -> ${registered.join(", ")}`);
    if (registered.length === 0) failures += 1;
  } catch (error) {
    console.log(`${name}: FAILED ${error?.message?.slice(0, 160)}`);
    failures += 1;
  }
}

console.log(failures === 0 ? "ALL TOOL PACKAGES REGISTERED" : `FAILURES: ${failures}`);
process.exit(failures === 0 ? 0 : 1);
