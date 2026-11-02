/**
 * MCA shell-env contributor (home-level DSH plugin).
 *
 * The official shell tools (`dsh-tool-bash`) do not inherit the host process environment: the
 * executor spawns `bash -c` with ENV_OVERRIDES + request env + `ctx.shellEnv.collect()`, and
 * `collect()` only emits DSH_HOME/DSH_SHELL/session/profile plus registered contributors. Ambient
 * `DSH_*` values are discarded by design.
 *
 * The bundled Android `bash` symlink is the command dispatcher (`libdshcmd.so`), and it requires
 * `DSH_PAYLOAD_DIR`; without this contributor every bash tool call fails with
 * "dsh: command dispatcher requires DSH_PAYLOAD_DIR (absolute payload root)" / exit 126
 * (device evidence: MiMo tool call, 2026-10-23). This plugin registers that one trusted fact.
 *
 * Only `DSH_*` keys may be registered: `dsh-shell-env` rejects any other name, and one rejected
 * key aborts the whole `register()` call (device evidence 2026-10-25: `declared invalid key
 * "TMPDIR"`, so `DSH_PAYLOAD_DIR` was never contributed either). `TMPDIR` is not a DSH fact and
 * does not need registration: the executor builds the child env from `scrubbedParentEnv()`, which
 * strips only `DSH_*` and sensitive names, so the host's app-private TMPDIR reaches the tool.
 */

export const name = 'mca-shell-env'

export const inject = ['shellEnv']

export function apply(ctx, config) {
  const payloadDir = String(config?.payloadDir ?? '').trim()
  if (payloadDir.length === 0) return
  ctx.shellEnv.register({
    name: 'mca-shell-env',
    variables: {
      DSH_PAYLOAD_DIR: {
        description: 'Absolute MCA DSH payload root required by the bundled command dispatcher.'
      }
    },
    resolve: () => ({ DSH_PAYLOAD_DIR: payloadDir })
  })
}
