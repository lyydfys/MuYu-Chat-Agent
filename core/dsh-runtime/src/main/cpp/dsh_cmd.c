/*
 * dsh_cmd.c - minimal exec dispatcher for the DSH payload command closure (D11).
 *
 * targetSdk 36 forbids exec() of files inside the app's writable home, so the payload's own
 * `/system/bin/sh` wrapper scripts (bin/bash, bin/npm, bin/npx, bin/pnpm, bin/pip, bin/python)
 * cannot be started by name: the official kernel's subprocess-local calls execvp("npm") and gets
 * EACCES. The only executable location is nativeLibraryDir, which only holds APK native libraries
 * renamed lib*.so. This tiny executable is packaged there (as libdshcmd.so) and the payload bin
 * names are symlinked to it; it reads the requested name from argv[0] and performs the same
 * translation the wrappers perform, but as a native exec (no shell, no shebang, no exec of a
 * writable script).
 *
 * Translation table (the real payload contents, see scripts/dsh/payload-lock.json):
 *   npm / npx          -> <node> <payload>/npm/bin/{npm,npx}-cli.js
 *   pnpm / pnpx        -> <node> <payload>/pnpm/bin/{pnpm,pnpx}.cjs  (pnpm keeps --config.package-import-method=copy)
 *   pip / pip3         -> <python> -m pip
 *   python/python3*    -> <python>                       (payload/python/bin/python3.14 launcher)
 *   node               -> <node>
 *   bash / sh          -> /system/bin/sh with bash-only leading options stripped (sh compatibility
 *                         layer; this is NOT GNU bash and never claims to be)
 *
 * <node>   = <nativeLibraryDir>/libdshnode.so
 * <python> = <nativeLibraryDir>/libdshpython.so
 * <nativeLibraryDir> = dirname of /proc/self/exe (this file), or $DSH_NATIVE_LIB_DIR
 * <payload> = $DSH_PAYLOAD_DIR (set by DshNodeCommand.environment for the DSH host), or the
 *             MCA layout fallback `<cwd>/../payload` when cwd is <files>/dsh/workspace and that
 *             sibling contains dshroot. The shellEnv contributor normally provides the variable;
 *             the fallback keeps bash alive if that home plugin is not applied (device evidence
 *             2026-10-25: exit 126 with the contributor row loaded-but-skipped).
 *
 * Anything not in the table exits 127 with a diagnostic on stderr; unknown names are never
 * silently forwarded. build: see core/dsh-runtime/src/main/cpp/CMakeLists.txt and
 * scripts/dsh/build-exec-dispatch.ps1.
 */

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define DSHP_MAX_ARGS 4096

static void fail(int code, const char *message) {
    fprintf(stderr, "dsh: %s\n", message);
    fflush(stderr);
    _exit(code);
}

static const char *base_name(const char *path) {
    if (path == NULL || *path == '\0') {
        return "";
    }
    const char *slash = strrchr(path, '/');
    return slash == NULL ? path : slash + 1;
}

static int is_bash_only_option(const char *arg) {
    static const char *const options[] = {
        "--noprofile", "--norc", "--login", "--posix", "--restricted", "-l"
    };
    for (size_t i = 0; i < sizeof(options) / sizeof(options[0]); i++) {
        if (strcmp(arg, options[i]) == 0) {
            return 1;
        }
    }
    /* --noprofile=<file> style long options carry a value. */
    return strncmp(arg, "--noprofile=", 12) == 0 || strncmp(arg, "--rcfile=", 9) == 0;
}

/* Path of the directory that holds this executable (nativeLibraryDir), no PATH search. */
static int native_library_dir(char *out, size_t capacity) {
    const char *from_env = getenv("DSH_NATIVE_LIB_DIR");
    if (from_env != NULL && from_env[0] == '/') {
        snprintf(out, capacity, "%s", from_env);
        return 0;
    }
    char executable[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    if (length <= 0) {
        return -1;
    }
    executable[length] = '\0';
    char *slash = strrchr(executable, '/');
    if (slash == NULL) {
        return -1;
    }
    *slash = '\0';
    snprintf(out, capacity, "%s", executable);
    return 0;
}

static int exec_direct(const char *executable, char *const argv[], const char *self) {
    execv(executable, argv);
    if (errno == ENOEXEC || errno == EACCES) {
        /* Last resort, matching DshNodeExecStrategy.Mode.LINKER64: run through the system linker. */
        char *linker_argv[DSHP_MAX_ARGS + 2];
        linker_argv[0] = "/system/bin/linker64";
        linker_argv[1] = (char *)executable;
        int copied = 0;
        while (argv[copied + 1] != NULL && copied + 2 < DSHP_MAX_ARGS) {
            linker_argv[copied + 2] = argv[copied + 1];
            copied++;
        }
        linker_argv[copied + 2] = NULL;
        execv("/system/bin/linker64", linker_argv);
    }
    fprintf(stderr, "dsh: cannot exec %s (for %s): %s\n", executable, self, strerror(errno));
    return -1;
}

/**
 * Resolve the payload root from MCA's fixed layout when DSH_PAYLOAD_DIR is absent. The official
 * bash tool starts every command inside <files>/dsh/workspace (possibly a nested session
 * workspace), so walk up to the DSH home that owns a `payload/dshroot` sibling. Both `bash`
 * (which does not need the payload at all) and the node/npm/pip entry points then work without
 * depending on a home-level plugin being applied. A cwd with no such ancestor keeps the old
 * failure instead of guessing.
 */
static int payload_from_cwd(char *out, size_t capacity) {
    char dir[PATH_MAX];
    if (getcwd(dir, sizeof(dir)) == NULL) return -1;
    for (int depth = 0; depth < 8; depth++) {
        char probe[PATH_MAX];
        int probe_len = snprintf(probe, sizeof(probe), "%s/payload/dshroot", dir);
        if (probe_len < 0) return -1;
        if (probe_len >= (int)sizeof(probe)) return -1;
        if (access(probe, F_OK) == 0) {
            char payload[PATH_MAX];
            if (snprintf(payload, sizeof(payload), "%s/payload", dir) >= (int)sizeof(payload)) {
                return -1;
            }
            char resolved[PATH_MAX];
            if (realpath(payload, resolved) == NULL) return -1;
            if (snprintf(out, capacity, "%s", resolved) >= (int)capacity) return -1;
            return 0;
        }
        char *slash = strrchr(dir, '/');
        if (slash == NULL || slash == dir) break;
        *slash = '\0';
    }
    return -1;
}

int main(int argc, char *argv[]) {
    const char *self = base_name(argc > 0 ? argv[0] : "");
    char native_dir[PATH_MAX];
    if (native_library_dir(native_dir, sizeof(native_dir)) != 0) {
        fail(126, "command dispatcher cannot resolve nativeLibraryDir (/proc/self/exe unreadable)");
    }
    char node[PATH_MAX];
    char python[PATH_MAX];
    snprintf(node, sizeof(node), "%s/libdshnode.so", native_dir);
    snprintf(python, sizeof(python), "%s/libdshpython.so", native_dir);

    const char *payload = getenv("DSH_PAYLOAD_DIR");
    char derived_payload[PATH_MAX];
    if (payload == NULL || payload[0] != '/') {
        if (payload_from_cwd(derived_payload, sizeof(derived_payload)) != 0) {
            fail(126, "command dispatcher requires DSH_PAYLOAD_DIR (absolute payload root)");
        }
        payload = derived_payload;
    }

    char **forwarded = calloc((size_t)(argc > 0 ? argc : 1) + 8, sizeof(char *));
    if (forwarded == NULL) {
        fail(126, "command dispatcher out of memory");
    }

    /* bash/sh: sh compatibility layer with the bash-only long options removed (argv preserved). */
    if (strcmp(self, "bash") == 0 || strcmp(self, "sh") == 0) {
        int index = 1;
        while (index < argc && is_bash_only_option(argv[index])) {
            index++;
        }
        forwarded[0] = (char *)"/system/bin/sh";
        int out = 1;
        while (index < argc && out < DSHP_MAX_ARGS) {
            forwarded[out++] = argv[index++];
        }
        forwarded[out] = NULL;
        execv("/system/bin/sh", forwarded);
        fprintf(stderr, "dsh: cannot exec /system/bin/sh: %s\n", strerror(errno));
        return 127;
    }

    /* Every other name starts a native executable plus (optionally) a JS/module entry point. */
    const char *js_entry = NULL;
    int module_mode = 0;
    if (strcmp(self, "node") == 0) {
        forwarded[0] = node;
    } else if (strcmp(self, "npm") == 0 || strcmp(self, "npx") == 0) {
        forwarded[0] = node;
        js_entry = strcmp(self, "npm") == 0 ? "/npm/bin/npm-cli.js" : "/npm/bin/npx-cli.js";
    } else if (strcmp(self, "pnpm") == 0 || strcmp(self, "pnpx") == 0) {
        forwarded[0] = node;
        js_entry = strcmp(self, "pnpm") == 0 ? "/pnpm/bin/pnpm.cjs" : "/pnpm/bin/pnpx.cjs";
    } else if (strcmp(self, "pip") == 0 || strcmp(self, "pip3") == 0) {
        forwarded[0] = python;
        module_mode = 1;
    } else if (strcmp(self, "python") == 0 || strcmp(self, "python3") == 0 ||
               strcmp(self, "python3.14") == 0) {
        forwarded[0] = python;
    } else {
        fprintf(stderr, "dsh: '%s' is not a DSH command dispatcher name (nativeLibraryDir=%s)\n",
                self, native_dir);
        return 127;
    }

    int out = 1;
    if (js_entry != NULL) {
        static char script[PATH_MAX];
        snprintf(script, sizeof(script), "%s%s", payload, js_entry);
        forwarded[out++] = script;
        if (strcmp(self, "pnpm") == 0) {
            forwarded[out++] = (char *)"--config.package-import-method=copy";
        }
    } else if (module_mode != 0) {
        forwarded[out++] = (char *)"-m";
        forwarded[out++] = (char *)"pip";
    }
    for (int index = 1; index < argc && out < DSHP_MAX_ARGS; index++) {
        forwarded[out++] = argv[index];
    }
    forwarded[out] = NULL;

    /* npm global prefix must live in the writable HOME, exactly like the payload wrapper. */
    if (js_entry != NULL && strstr(js_entry, "npm/bin") != NULL) {
        const char *home = getenv("HOME");
        if (home != NULL && home[0] == '/') {
            static char prefix[PATH_MAX];
            snprintf(prefix, sizeof(prefix), "%s/.npm-global", home);
            setenv("NPM_CONFIG_PREFIX", prefix, 1);
        }
    }
    /* Library roots are engine-wide; the host sets them, but a hand-run dispatcher must still work. */
    if (getenv("LD_LIBRARY_PATH") == NULL) {
        static char libraries[PATH_MAX];
        snprintf(libraries, sizeof(libraries), "%s/runtime/lib", payload);
        setenv("LD_LIBRARY_PATH", libraries, 0);
    }
    if (getenv("PYTHONHOME") == NULL) {
        static char python_home[PATH_MAX];
        snprintf(python_home, sizeof(python_home), "%s/python", payload);
        setenv("PYTHONHOME", python_home, 0);
    }

    exec_direct(forwarded[0], forwarded, self);
    return 127;
}
