/**
 * MCA terminal seam for the official DSH kernel — a home-level Cordis plugin.
 *
 * The official terminal chain is
 *   `dsh-tool-terminal` -> `ctx.terminals` -> `dsh-terminal-bash` -> `ctx.subprocess.spawnTerminal`.
 * The payload's `@deepseek-ai/dsh-subprocess-local` builds a pure-JS `/dev/ptmx` + `/dev/pts/N`
 * pseudo terminal, and on an ordinary Android app that scan fails, so it degrades to pipes with a
 * JavaScript line discipline: no `isatty`, no `TIOCSWINSZ`, no tty `^C` to a foreground group, and
 * a `resize()` that is a no-op. MCA owns the only real PTY (`libdshpty.so`, `posix_openpt` +
 * `grantpt` + `unlockpt` + `setsid` + `TIOCSCTTY` + `TIOCSWINSZ` + process-group stop) inside the
 * App process, reachable over the authenticated App-local capability bridge.
 *
 * This plugin does not patch payload bytes. It wraps the `subprocess` service instance so the
 * official backend's `spawnTerminal(spec)` forwards to that bridge and returns the handle shape the
 * official `dsh-terminal-bash` session expects:
 *
 *   output (Readable of Buffer chunks) · done ({exitCode, signal}) · write(data) ·
 *   resize(cols, rows) · terminate() · pid · running · waitForExit()
 *
 * Honesty contract (never violated here):
 *  - `inspectForeground()` resolves `undefined` ("unknown"), never an invented pgid, because the
 *    bridge exposes no process-group introspection (`TIOCGPGRP`/`ps`) across the process boundary.
 *  - `signalForeground()` throws instead of reporting a delivered signal with a fabricated target.
 *  - an `argv` this bridge cannot honour byte-for-byte (anything but the Android shell and
 *    interactive/login/profile flags) is refused with a typed error instead of being dropped.
 *  - a bridge failure is thrown; it is never silently downgraded to the payload's pipe "PTY".
 *  - when `APP_NOTIFY_PORT`/`APP_LOCAL_TOKEN` are absent the payload path is kept untouched and the
 *    degradation is logged, so a generation without the bridge is never misreported as a real PTY.
 *
 * Dependency-free on purpose: `node:` builtins only. MCA must not add a package to the verified
 * payload tree, and the seam is installed as one file under `<DSH_HOME>/seams/terminal-bridge/`.
 *
 * Bridges to `POST /terminal/{open,read,write,resize,close}` with `X-DSH-Token`; `open` and `close`
 * also carry `X-DSH-Request-Id` so the bridge ledger can replay an open that was retried instead of
 * creating a second terminal.
 */

import { PassThrough } from 'node:stream'

export const name = 'mca-terminal-bridge'

/** The bridge can only attach after the subprocess service it wraps exists. */
export const inject = ['subprocess']

const OPEN_PATH = '/terminal/open'
const READ_PATH = '/terminal/read'
const WRITE_PATH = '/terminal/write'
const RESIZE_PATH = '/terminal/resize'
const CLOSE_PATH = '/terminal/close'

/** Bounded read window; the bridge caps `maxBytes` at 256 KiB. */
const READ_BYTES = 64 * 1024
/** Poll cadence while the child is alive. Short enough for a prompt, long enough to stay cheap. */
const POLL_MILLIS = 40
const CALL_TIMEOUT_MILLIS = 15_000
const OPEN_TIMEOUT_MILLIS = 30_000
const CLOSE_TIMEOUT_MILLIS = 20_000

/** Exactly the flags the Android `sh` compatibility layer can honour for an interactive shell. */
const SHELL_ARGUMENT_PREFIX = ['--noprofile', '--norc', '--posix', '--restricted', '--login']
const SHELL_ARGUMENT_FLAG = ['-i', '-s', '-l']

const MAX_COLS = 1_000
const MAX_ROWS = 1_000
const DEFAULT_COLS = 80
const DEFAULT_ROWS = 24

const SIGNAL_NAMES = new Map([
  [1, 'SIGHUP'],
  [2, 'SIGINT'],
  [3, 'SIGQUIT'],
  [6, 'SIGABRT'],
  [9, 'SIGKILL'],
  [11, 'SIGSEGV'],
  [13, 'SIGPIPE'],
  [14, 'SIGALRM'],
  [15, 'SIGTERM']
])

let requestSequence = 0

/** 16..64 URL-safe characters, unique per call: the bridge rejects and never replays otherwise. */
function newRequestId(kind) {
  requestSequence += 1
  const random = Math.random().toString(36).slice(2, 10)
  return `mca-${kind}-${Date.now().toString(36)}-${requestSequence.toString(36)}-${random}`.slice(0, 64)
}

/** A missing logger must never break a terminal. */
function note(ctx, message) {
  try {
    if (typeof ctx?.logger?.info === 'function') ctx.logger.info(`mca-terminal-bridge: ${message}`)
    else if (typeof ctx?.logger?.warn === 'function') ctx.logger.warn(`mca-terminal-bridge: ${message}`)
  } catch {
    /* logging is advisory */
  }
}

function warn(ctx, message) {
  try {
    if (typeof ctx?.logger?.warn === 'function') ctx.logger.warn(`mca-terminal-bridge: ${message}`)
  } catch {
    /* logging is advisory */
  }
}

/** `null` means "this generation has no App bridge"; the payload path then stays in charge. */
function bridgeConfig() {
  const port = Number.parseInt(String(process.env.APP_NOTIFY_PORT ?? ''), 10)
  const token = String(process.env.APP_LOCAL_TOKEN ?? '')
  if (!Number.isInteger(port) || port < 1 || port > 65_535) return null
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(token)) return null
  return { port, token }
}

function boundedInteger(value, min, max) {
  if (!Number.isSafeInteger(value)) return undefined
  if (value < min || value > max) return undefined
  return value
}

function signalName(value) {
  if (!Number.isSafeInteger(value)) return undefined
  return SIGNAL_NAMES.get(value) ?? `SIG${value}`
}

function failureText(answer) {
  const reason = typeof answer?.json?.reason === 'string' ? answer.json.reason : undefined
  const detail = typeof answer?.json?.detail === 'string' ? answer.json.detail : undefined
  const status = typeof answer?.status === 'number' ? `HTTP ${answer.status}` : 'no status'
  return [reason, detail, status].filter((part) => part !== undefined && part !== '').join(': ')
}

/** Minimal client of the authenticated loopback capability bridge. */
function createClient(config) {
  const baseUrl = `http://127.0.0.1:${config.port}`

  async function post(path, body, options = {}) {
    const headers = {
      'Content-Type': 'application/json; charset=utf-8',
      'X-DSH-Token': config.token
    }
    if (options.requestId !== undefined) headers['X-DSH-Request-Id'] = options.requestId
    const controller = new AbortController()
    const timeoutMillis = options.timeoutMillis ?? CALL_TIMEOUT_MILLIS
    const timer = setTimeout(() => controller.abort(), timeoutMillis)
    if (typeof timer.unref === 'function') timer.unref()
    let status
    let text
    try {
      const response = await fetch(`${baseUrl}${path}`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body ?? {}),
        signal: controller.signal
      })
      status = response.status
      text = await response.text()
    } catch (error) {
      const name = error?.name ?? 'Error'
      if (name === 'AbortError') {
        throw new Error(`MCA terminal bridge ${path} did not answer within ${timeoutMillis}ms`)
      }
      throw new Error(`MCA terminal bridge ${path} is unreachable (${name}); the App capability bridge is not serving this generation`)
    } finally {
      clearTimeout(timer)
    }
    let json
    try {
      json = JSON.parse(text)
    } catch {
      throw new Error(`MCA terminal bridge ${path} answered HTTP ${status} with a non-JSON body`)
    }
    return { status, json }
  }

  return { baseUrl, post }
}

/**
 * The bridge owns one fixed shell per generation, so an argv that would change semantics
 * (`-c command`, another program, a script path) is refused instead of silently substituted.
 */
function assertShellArguments(argv) {
  const program = argv[0]
  const base = String(program).split('/').pop() ?? ''
  if (base !== 'bash' && base !== 'sh') {
    throw new Error(`MCA terminal bridge owns only the Android shell (bash/sh compatibility layer); refusing to start ${JSON.stringify(program)}`)
  }
  for (const argument of argv.slice(1)) {
    if (SHELL_ARGUMENT_PREFIX.includes(argument) || SHELL_ARGUMENT_FLAG.includes(argument)) continue
    throw new Error(`MCA terminal bridge cannot honour argv ${JSON.stringify(argument)}: the App PTY runs the Android shell without extra arguments`)
  }
}

async function openSession(client, spec) {
  const argv = Array.isArray(spec?.argv) ? spec.argv.map((value) => String(value)) : []
  if (argv.length === 0) throw new Error('MCA terminal bridge needs an argv[0]')
  assertShellArguments(argv)
  const body = {
    type: 'shell',
    cols: boundedInteger(spec?.cols, 1, MAX_COLS) ?? DEFAULT_COLS,
    rows: boundedInteger(spec?.rows, 1, MAX_ROWS) ?? DEFAULT_ROWS,
    requirePty: true
  }
  const cwd = typeof spec?.cwd === 'string' && spec.cwd.length > 0 ? spec.cwd : undefined
  if (cwd !== undefined) body.cwd = cwd
  const answer = await client.post(OPEN_PATH, body, {
    requestId: newRequestId('open'),
    timeoutMillis: OPEN_TIMEOUT_MILLIS
  })
  if (answer.status !== 200 || answer.json?.ok !== true) {
    throw new Error(`MCA terminal bridge refused the session: ${failureText(answer)}`)
  }
  const session = answer.json.session ?? {}
  if (typeof session.sessionId !== 'string' || session.sessionId.length === 0) {
    throw new Error('MCA terminal bridge opened a session without a session id')
  }
  const motd = typeof answer.json.motd === 'string' ? answer.json.motd : ''
  return {
    sessionId: session.sessionId,
    pid: boundedInteger(session.pid, 1, 2_147_483_647),
    motd,
    nextOffset: Number.isSafeInteger(session.nextOffset) ? session.nextOffset : 0,
    transport: typeof session.transport === 'string' ? session.transport : 'UNKNOWN',
    realPty: session.realPty === true,
    compatibility: {
      transport: typeof session.transport === 'string' ? session.transport : 'UNKNOWN',
      realPty: session.realPty === true,
      requestedProgram: argv[0],
      requestedArguments: argv.slice(1),
      /** The payload's `dsh-terminal-bash` passes `dumb`; the App PTY does not rewrite TERM. */
      requestedTerminalType: typeof spec?.terminalType === 'string' ? spec.terminalType : undefined,
      /** Sandbox argv/env/grace are not byte-faithful through this bridge; it is reported, not hidden. */
      sandboxArgvByteFaithful: false,
      gnuBash: false,
      shell: 'Android sh compatibility layer'
    }
  }
}

/**
 * The handle the official terminal backend consumes. Every method that cannot be honoured throws
 * instead of reporting a synthetic success.
 */
class BridgeTerminal {
  constructor(client, opened, ctx) {
    this.client = client
    this.ctx = ctx
    this.sessionId = opened.sessionId
    this.pid = opened.pid
    this.transport = opened.compatibility.transport
    this.mcaCompatibility = opened.compatibility
    this.output = new PassThrough()
    this.done = new Promise((resolve, reject) => {
      this.settleDone = resolve
      this.failDone = reject
    })
    // Attaching a no-op handler keeps an *ignored* `done` from crashing the kernel process, while
    // every caller that awaits `done` still receives the exact rejection. The official backend
    // always observes it; a second consumer must not be able to take the host down either.
    this.done.catch(() => {})
    this.offset = opened.nextOffset
    this.finished = false
    this.settled = false
    this.pollTimer = undefined
    this.pollInFlight = false
    this.pollFailures = 0
    this.closePromise = undefined
    this.resizeWarned = false
    if (opened.motd.length > 0) this.output.write(Buffer.from(opened.motd, 'utf8'))
    this.schedulePoll(0)
  }

  /** LocalTerminalHandle parity: the backend checks this before writing. */
  get running() {
    return !this.finished
  }

  waitForExit() {
    return this.done
  }

  schedulePoll(delayMillis) {
    if (this.finished) return
    const timer = setTimeout(() => {
      this.pollTimer = undefined
      this.poll()
    }, delayMillis)
    if (typeof timer.unref === 'function') timer.unref()
    this.pollTimer = timer
  }

  async poll() {
    if (this.finished || this.pollInFlight) return
    this.pollInFlight = true
    let answer
    try {
      answer = await this.client.post(READ_PATH, {
        sessionId: this.sessionId,
        offset: this.offset,
        maxBytes: READ_BYTES
      })
    } catch (error) {
      this.pollInFlight = false
      this.failTransport(error)
      return
    }
    this.pollInFlight = false
    if (this.finished) return
    if (answer.json?.ok !== true) {
      this.failTransport(new Error(`MCA terminal bridge read failed: ${failureText(answer)}`))
      return
    }
    this.pollFailures = 0
    const page = answer.json
    if (typeof page.text === 'string' && page.text.length > 0) {
      this.output.write(Buffer.from(page.text, 'utf8'))
    }
    if (Number.isSafeInteger(page.nextOffset)) this.offset = page.nextOffset
    const status = typeof page.status === 'string' ? page.status : 'RUNNING'
    if (status === 'RUNNING') {
      this.schedulePoll(POLL_MILLIS)
      return
    }
    // Match the official outcome shape: a signalled child reports `signal` and no exit code.
    const exitedBySignal = Number.isSafeInteger(page.signal)
    this.finish({
      exitCode: exitedBySignal
        ? undefined
        : Number.isSafeInteger(page.exitCode)
          ? page.exitCode
          : undefined,
      signal: signalName(page.signal)
    })
  }

  failTransport(error) {
    if (this.finished) return
    this.finished = true
    if (this.pollTimer !== undefined) {
      clearTimeout(this.pollTimer)
      this.pollTimer = undefined
    }
    // `done` rejects for every consumer; the stream only carries the error when the consumer
    // listens for it, because an unhandled 'error' event would take the host process down.
    if (this.output.listenerCount('error') > 0) this.output.destroy(error)
    else this.output.destroy()
    if (!this.settled) {
      this.settled = true
      this.failDone(error)
    }
  }

  finish(outcome) {
    if (this.finished) return
    this.finished = true
    if (this.pollTimer !== undefined) {
      clearTimeout(this.pollTimer)
      this.pollTimer = undefined
    }
    this.output.end()
    if (!this.settled) {
      this.settled = true
      this.settleDone(outcome ?? {})
    }
  }

  async write(data) {
    if (this.finished) throw new Error('MCA terminal session has exited')
    const text = typeof data === 'string' ? data : Buffer.from(data ?? '').toString('utf8')
    if (text.length === 0) return
    const answer = await this.client.post(WRITE_PATH, {
      sessionId: this.sessionId,
      text,
      submit: false
    })
    if (answer.json?.ok !== true) {
      throw new Error(`MCA terminal bridge refused the write: ${failureText(answer)}`)
    }
  }

  async resize(cols, rows) {
    if (this.finished) return
    const safeCols = boundedInteger(cols, 1, MAX_COLS)
    const safeRows = boundedInteger(rows, 1, MAX_ROWS)
    if (safeCols === undefined || safeRows === undefined) return
    const answer = await this.client.post(RESIZE_PATH, {
      sessionId: this.sessionId,
      cols: safeCols,
      rows: safeRows
    })
    if (answer.json?.ok === true) return
    if (answer.json?.reason === 'resize-unsupported') {
      // The explicit pipe fallback has no kernel geometry. Reported once, never faked as applied.
      if (!this.resizeWarned) {
        this.resizeWarned = true
        warn(this.ctx, `resize is unsupported on the ${this.transport} transport; no geometry was applied`)
      }
      return
    }
    throw new Error(`MCA terminal bridge refused the resize: ${failureText(answer)}`)
  }

  /** The bridge has no pgid introspection across the process boundary: report unknown. */
  async inspectForeground() {
    return undefined
  }

  /** The bridge cannot target a foreground group, so it must not claim a delivery. */
  async signalForeground(signal) {
    throw new Error(`MCA terminal bridge cannot signal the foreground process group (${signal}): the App PTY owns it and exposes no pgid; write the byte through terminal input instead`)
  }

  async inspectActivity() {
    return { state: 'unknown', revision: 0 }
  }

  terminate() {
    if (this.closePromise !== undefined) return this.closePromise
    const closing = this.closeOnce().catch((error) => {
      this.closePromise = undefined
      throw error
    })
    this.closePromise = closing
    return closing
  }

  /** Force-terminate during host exit; quiescence is not claimed here. */
  terminateForHostExit() {
    this.terminate().catch(() => {})
  }

  async closeOnce() {
    if (this.pollTimer !== undefined) {
      clearTimeout(this.pollTimer)
      this.pollTimer = undefined
    }
    const answer = await this.client.post(
      CLOSE_PATH,
      { sessionId: this.sessionId },
      { requestId: newRequestId('close'), timeoutMillis: CLOSE_TIMEOUT_MILLIS }
    )
    if (answer.json?.settled !== true) {
      this.failTransport(new Error(`MCA terminal bridge could not confirm the session stop: ${failureText(answer)}`))
      throw new Error(`MCA terminal bridge could not confirm the session stop: ${failureText(answer)}`)
    }
    this.finish(undefined)
  }
}

/**
 * Cordis plugin entry. Wrapping the live service instance (instead of re-registering the service)
 * keeps exactly one `ctx.subprocess` identity: the official backend already holds it.
 */
export function apply(ctx) {
  const config = bridgeConfig()
  if (config === null) {
    warn(
      ctx,
      'APP_NOTIFY_PORT/APP_LOCAL_TOKEN are absent for this generation, so the official subprocess seam keeps the payload PTY path and the App real PTY stays unused'
    )
    return
  }
  const service = ctx?.subprocess
  if (service === undefined || typeof service.spawnTerminal !== 'function') {
    warn(ctx, 'ctx.subprocess.spawnTerminal is unavailable; the App PTY cannot be attached')
    return
  }
  const client = createClient(config)
  const original = service.spawnTerminal.bind(service)
  service.spawnTerminal = async function spawnTerminalViaAppPty(spec) {
    const opened = await openSession(client, spec)
    const terminal = new BridgeTerminal(client, opened, ctx)
    const signal = spec?.signal
    if (signal !== undefined && typeof signal.addEventListener === 'function') {
      if (signal.aborted === true) {
        terminal.terminate().catch(() => {})
      } else {
        signal.addEventListener('abort', () => {
          terminal.terminate().catch(() => {})
        }, { once: true })
      }
    }
    note(
      ctx,
      `terminal forwarded to the App PTY session=${opened.sessionId} transport=${opened.compatibility.transport} ` +
        `realPty=${opened.compatibility.realPty} (sandbox argv/env are not byte-faithful; gnuBash=false)`
    )
    return terminal
  }
  ctx.on?.('dispose', () => {
    service.spawnTerminal = original
  })
  note(ctx, `spawnTerminal now forwards to the App capability bridge at ${client.baseUrl}`)
}

