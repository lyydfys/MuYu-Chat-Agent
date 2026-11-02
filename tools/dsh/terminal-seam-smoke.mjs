/**
 * Dependency-free smoke check of the MCA terminal seam (`assets/dsh-seam/terminal-bridge.mjs`).
 *
 * The App-side PTY cannot run in a JVM test, and the repository has no JS test harness, so this
 * script is the closest honest check available without a device:
 *   - a fake loopback "capability bridge" implements POST /terminal/{open,read,write,resize,close};
 *   - a fake Cordis context exposes `subprocess.spawnTerminal`, exactly like the pinned
 *     `@deepseek-ai/dsh-subprocess-local` service;
 *   - the seam is applied, `spawnTerminal` is called with the official spec shape, and the returned
 *     handle is exercised: output chunks, done, write, resize, terminate, inspectForeground,
 *     signalForeground.
 *
 * Run: node tools/dsh/terminal-seam-smoke.mjs
 * It never touches a device, a Gradle build or the network beyond 127.0.0.1.
 */

import { createServer } from 'node:http'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, resolve } from 'node:path'
import assert from 'node:assert/strict'

const here = dirname(fileURLToPath(import.meta.url))
const seamPath = resolve(
  here,
  '../../core/dsh-runtime/src/main/assets/dsh-seam/terminal-bridge.mjs'
)

const TOKEN = 'dshc_' + 'A'.repeat(43)
const SESSION = 'term-1'

const calls = []
let readCount = 0
let closeSettled = true
let writeBody = null
let resizeBody = null

const server = createServer((request, response) => {
  let body = ''
  request.setEncoding('utf8')
  request.on('data', (chunk) => {
    body += chunk
  })
  request.on('end', () => {
    const parsed = body.length > 0 ? JSON.parse(body) : {}
    calls.push({ path: request.url, headers: request.headers, body: parsed })
    const send = (status, payload) => {
      const text = JSON.stringify(payload)
      response.writeHead(status, { 'Content-Type': 'application/json' })
      response.end(text)
    }
    if (request.headers['x-dsh-token'] !== TOKEN) {
      send(401, { ok: false, reason: 'unauthorized' })
      return
    }
    switch (request.url) {
      case '/terminal/open':
        send(200, {
          ok: true,
          motd: 'mca$ ',
          session: {
            sessionId: SESSION,
            transport: 'REAL_PTY',
            realPty: true,
            pid: 4242,
            status: 'RUNNING',
            cols: parsed.cols,
            rows: parsed.rows,
            nextOffset: 5
          }
        })
        return
      case '/terminal/read': {
        readCount += 1
        // The fake shell emits its banner first, then exits only after it received input, so the
        // harness is deterministic instead of racing the poll loop.
        if (parsed.offset < 12) {
          send(200, {
            ok: true,
            sessionId: SESSION,
            text: 'hello\r\n',
            bytes: 7,
            nextOffset: 12,
            truncated: false,
            status: 'RUNNING'
          })
        } else if (writeBody === null) {
          send(200, {
            ok: true,
            sessionId: SESSION,
            text: '',
            bytes: 0,
            nextOffset: 12,
            truncated: false,
            status: 'RUNNING'
          })
        } else {
          send(200, {
            ok: true,
            sessionId: SESSION,
            text: 'bye\r\n',
            bytes: 5,
            nextOffset: 17,
            truncated: false,
            status: 'EXITED',
            exitCode: 0,
            signal: null
          })
        }
        return
      }
      case '/terminal/write':
        writeBody = parsed
        send(200, { ok: true, bytes: String(parsed.text).length })
        return
      case '/terminal/resize':
        resizeBody = parsed
        send(200, { ok: true, applied: true, coalesced: false, transport: 'REAL_PTY', cols: parsed.cols, rows: parsed.rows })
        return
      case '/terminal/close':
        send(closeSettled ? 200 : 409, {
          ok: closeSettled,
          settled: closeSettled,
          escalated: false,
          detail: 'stopped'
        })
        return
      default:
        send(404, { ok: false, reason: 'not-found' })
    }
  })
})

function listen() {
  return new Promise((resolvePromise) => {
    server.listen(0, '127.0.0.1', () => resolvePromise(server.address().port))
  })
}

function logger() {
  const lines = []
  return {
    lines,
    info: (message) => lines.push(`info ${message}`),
    warn: (message) => lines.push(`warn ${message}`)
  }
}

async function main() {
  const port = await listen()
  process.env.APP_NOTIFY_PORT = String(port)
  process.env.APP_LOCAL_TOKEN = TOKEN

  const seam = await import(pathToFileURL(seamPath).href)
  const original = async () => {
    throw new Error('the payload PTY must not be used when the bridge is configured')
  }
  const ctx = {
    logger: logger(),
    listeners: {},
    on(event, handler) {
      this.listeners[event] = handler
    },
    subprocess: { spawnTerminal: original }
  }

  seam.apply(ctx)
  assert.equal(typeof ctx.subprocess.spawnTerminal, 'function')
  assert.notEqual(ctx.subprocess.spawnTerminal, original, 'spawnTerminal must be wrapped')

  const terminal = await ctx.subprocess.spawnTerminal({
    argv: ['/bin/bash', '-i'],
    cwd: '/data/user/0/com.muyuchat.mca/files/dsh/workspace',
    env: { TERM: 'dumb' },
    rows: 30,
    cols: 100,
    terminalType: 'dumb',
    graceMs: 1_000
  })

  // The open call carries the token, a request id and the requested geometry.
  const open = calls.find((call) => call.path === '/terminal/open')
  assert.equal(open.headers['x-dsh-token'], TOKEN)
  assert.match(open.headers['x-dsh-request-id'], /^[A-Za-z0-9_-]{16,64}$/)
  assert.equal(open.body.cols, 100)
  assert.equal(open.body.rows, 30)
  assert.equal(open.body.requirePty, true)
  assert.equal(open.body.type, 'shell')

  // The handle is the shape `dsh-terminal-bash` consumes.
  assert.equal(terminal.pid, 4242)
  assert.equal(terminal.transport, 'REAL_PTY')
  assert.equal(terminal.mcaCompatibility.gnuBash, false)
  assert.equal(typeof terminal.write, 'function')
  assert.equal(typeof terminal.resize, 'function')
  assert.equal(typeof terminal.terminate, 'function')
  assert.equal(terminal.done instanceof Promise, true)

  // Output: the startup capture (`motd`) is replayed, then the polled pages.
  const chunks = []
  terminal.output.on('data', (chunk) => chunks.push(chunk.toString('utf8')))

  // Input is written verbatim while the child is alive: the official backend already appends "\r"
  // when it submits, so the seam must not add a second one.
  await terminal.write('echo hello\r')
  assert.deepEqual(writeBody, { sessionId: SESSION, text: 'echo hello\r', submit: false })

  // Resize goes through the bridge and is applied by the real PTY.
  await terminal.resize(90, 40)
  assert.deepEqual(resizeBody, { sessionId: SESSION, cols: 90, rows: 40 })

  const outcome = await terminal.done
  assert.deepEqual(chunks, ['mca$ ', 'hello\r\n', 'bye\r\n'])
  assert.deepEqual(outcome, { exitCode: 0, signal: undefined })
  // Every poll advances the cursor instead of re-reading the same bytes.
  const reads = calls.filter((call) => call.path === '/terminal/read')
  assert.equal(reads[0].body.offset, 5)
  assert.equal(reads[1].body.offset, 12)
  assert.equal(terminal.running, false)

  // Foreground introspection is honestly unavailable: no invented pgid, no delivered signal.
  assert.equal(await terminal.inspectForeground(), undefined)
  await assert.rejects(() => terminal.signalForeground('SIGINT'), /cannot signal the foreground process group/)

  // terminate() closes the session and requires a settled stop.
  await terminal.terminate()
  const close = calls.find((call) => call.path === '/terminal/close')
  assert.match(close.headers['x-dsh-request-id'], /^[A-Za-z0-9_-]{16,64}$/)

  // An unsettled stop must throw instead of claiming success.
  closeSettled = false
  const second = await ctx.subprocess.spawnTerminal({ argv: ['/bin/sh', '-i'], cols: 80, rows: 24 })
  await assert.rejects(() => second.terminate(), /could not confirm the session stop/)

  // A non-shell program is refused instead of being silently substituted.
  await assert.rejects(
    () => ctx.subprocess.spawnTerminal({ argv: ['/data/local/tmp/tool'], cols: 80, rows: 24 }),
    /owns only the Android shell/
  )
  // An argv that would change semantics is refused too.
  await assert.rejects(
    () => ctx.subprocess.spawnTerminal({ argv: ['/bin/bash', '-c', 'id'], cols: 80, rows: 24 }),
    /cannot honour argv/
  )

  console.log('terminal seam smoke: PASS')
  console.log(`  calls: ${calls.map((call) => call.path).join(', ')}`)
  console.log(`  logger: ${ctx.logger.lines.length} line(s)`)
}

main()
  .then(async () => {
    await new Promise((resolveClose) => server.close(resolveClose))
  })
  .catch(async (error) => {
    await new Promise((resolveClose) => server.close(resolveClose))
    console.error('terminal seam smoke: FAIL')
    console.error(error)
    process.exitCode = 1
  })
