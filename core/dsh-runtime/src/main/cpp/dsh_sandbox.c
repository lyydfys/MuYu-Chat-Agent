/*
 * mca-dsh-sandbox: Android Landlock runner for the official DSH sandbox seam.
 *
 * The pinned official dsh-sandbox-local package accepts an operator
 * `runnerCommand`; when configured it wraps every command as:
 *
 *   <runner> <bwrap-compatible profile args> -- <argv...>
 *
 * This runner understands exactly the profile bwrapProfileArgs() emits
 * (--ro-bind/--bind/--tmpfs/--dev/--proc/--unshare-pid/--die-with-parent),
 * applies an equivalent Landlock write policy to itself and then execs the
 * target. Reads and exec are not handled by the ruleset, so they stay
 * unrestricted like the bubblewrap profile; only file-creating/removing/
 * writing access is confined.
 *
 * Android has no /tmp and cannot mount a private tmpfs without privileges, so
 * `--tmpfs /tmp` is mapped to $TMPDIR (the host pins it to the app-private
 * DSH tmp root) and, failing that, to a real /tmp when one exists. The mapping
 * is the Android equivalent of the ephemeral tmpfs: an app-owned temp area
 * inside the declared writable set.
 *
 * Exit codes: 125 for every launcher-level failure (unsupported kernel,
 * unparsable profile, unopenable grant root, failed confinement). After a
 * successful exec the wrapped command may also return 125; consumers require
 * the `mca-dsh-sandbox: ` stderr prefix, so target failures are printed
 * without that prefix and use 127.
 *
 * The file is compiled as a PIE executable and staged as
 * jniLibs/arm64-v8a/libdshsandbox.so by scripts/dsh/build-sandbox-runner.ps1,
 * the same model as the D11 command dispatcher: AGP packages shared libraries,
 * not add_executable() targets.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/landlock.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#ifndef __NR_landlock_create_ruleset
#define __NR_landlock_create_ruleset 444
#endif
#ifndef __NR_landlock_add_rule
#define __NR_landlock_add_rule 445
#endif
#ifndef __NR_landlock_restrict_self
#define __NR_landlock_restrict_self 446
#endif
#ifndef LANDLOCK_ACCESS_FS_REFER
#define LANDLOCK_ACCESS_FS_REFER (1ULL << 13)
#endif
#ifndef LANDLOCK_ACCESS_FS_TRUNCATE
#define LANDLOCK_ACCESS_FS_TRUNCATE (1ULL << 14)
#endif

#define LAUNCHER_FAILURE_EXIT 125
#define MAX_GRANTS 8

static void diag(const char *format, ...) {
    char buffer[512];
    va_list args;
    va_start(args, format);
    int length = vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    if (length < 0) return;
    if ((size_t)length >= sizeof(buffer)) length = (int)sizeof(buffer) - 1;
    (void)write(STDERR_FILENO, "mca-dsh-sandbox: ", 17);
    (void)write(STDERR_FILENO, buffer, (size_t)length);
    (void)write(STDERR_FILENO, "\n", 1);
}

static int landlock_abi(void) {
    long result = syscall(__NR_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    if (result < 0) return -1;
    return (int)result;
}

/* The write-shaped subset of the ABI vocabulary this kernel understands. */
static uint64_t write_rights(int abi) {
    uint64_t rights = LANDLOCK_ACCESS_FS_WRITE_FILE |
                      LANDLOCK_ACCESS_FS_REMOVE_DIR |
                      LANDLOCK_ACCESS_FS_REMOVE_FILE |
                      LANDLOCK_ACCESS_FS_MAKE_CHAR |
                      LANDLOCK_ACCESS_FS_MAKE_DIR |
                      LANDLOCK_ACCESS_FS_MAKE_REG |
                      LANDLOCK_ACCESS_FS_MAKE_SOCK |
                      LANDLOCK_ACCESS_FS_MAKE_FIFO |
                      LANDLOCK_ACCESS_FS_MAKE_BLOCK |
                      LANDLOCK_ACCESS_FS_MAKE_SYM;
    if (abi >= 2) rights |= LANDLOCK_ACCESS_FS_REFER;
    if (abi >= 3) rights |= LANDLOCK_ACCESS_FS_TRUNCATE;
    return rights;
}

static int create_ruleset(uint64_t handled) {
    struct landlock_ruleset_attr attr;
    memset(&attr, 0, sizeof(attr));
    attr.handled_access_fs = handled;
    /* Pass the minimum documented size so kernels older than the header's
     * struct still accept the ruleset; zero-filled trailing fields are fine. */
    return (int)syscall(__NR_landlock_create_ruleset, &attr, sizeof(uint64_t), 0);
}

static int grant_path(int ruleset_fd, const char *path, uint64_t handled) {
    struct stat info;
    if (stat(path, &info) != 0) return -1;
    uint64_t allowed = S_ISDIR(info.st_mode) ? handled : LANDLOCK_ACCESS_FS_WRITE_FILE;
    int fd = open(path, O_PATH | O_CLOEXEC);
    if (fd < 0) return -1;
    struct landlock_path_beneath_attr rule;
    memset(&rule, 0, sizeof(rule));
    rule.allowed_access = allowed;
    rule.parent_fd = fd;
    int result = (int)syscall(__NR_landlock_add_rule, ruleset_fd,
                              LANDLOCK_RULE_PATH_BENEATH, &rule, 0);
    int saved = errno;
    close(fd);
    errno = saved;
    return result;
}

static int enforce(int ruleset_fd) {
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return -1;
    return (int)syscall(__NR_landlock_restrict_self, ruleset_fd, 0);
}

static int apply_ruleset(uint64_t handled, const char *const *roots, const int *is_dir, int count) {
    int abi = landlock_abi();
    if (abi < 1) {
        diag("Landlock is unavailable on this kernel (%s)", strerror(errno));
        return -1;
    }
    int ruleset_fd = create_ruleset(handled);
    if (ruleset_fd < 0) {
        diag("cannot create the Landlock ruleset (%s)", strerror(errno));
        return -1;
    }
    if (grant_path(ruleset_fd, "/dev/null", handled) != 0) {
        diag("cannot grant /dev/null (%s)", strerror(errno));
        close(ruleset_fd);
        return -1;
    }
    for (int index = 0; index < count; index++) {
        const char *root = roots[index];
        if (root == NULL || root[0] == '\0') continue;
        if (!is_dir[index]) {
            diag("grant root is not a directory: %s", root);
            close(ruleset_fd);
            return -1;
        }
        if (grant_path(ruleset_fd, root, handled) != 0) {
            diag("cannot grant writable root %s (%s)", root, strerror(errno));
            close(ruleset_fd);
            return -1;
        }
    }
    if (enforce(ruleset_fd) != 0) {
        diag("cannot enforce the Landlock ruleset (%s)", strerror(errno));
        close(ruleset_fd);
        return -1;
    }
    close(ruleset_fd);
    return abi;
}

/*
 * Functional probe: prove that the running kernel both accepts and enforces a
 * write ruleset, not merely that the syscalls exist. The current directory must
 * be writable by the caller and must be outside the single granted subdirectory;
 * the seeder runs the probe with cwd at the app-private DSH data root.
 */
static int run_probe(void) {
    int abi = landlock_abi();
    if (abi < 1) {
        diag("Landlock is unavailable on this kernel (%s)", strerror(errno));
        return LAUNCHER_FAILURE_EXIT;
    }
    char cwd[PATH_MAX];
    if (getcwd(cwd, sizeof(cwd)) == NULL) {
        diag("cannot resolve the probe working directory (%s)", strerror(errno));
        return LAUNCHER_FAILURE_EXIT;
    }
    char granted[PATH_MAX];
    if (snprintf(granted, sizeof(granted), "%s/.mca-sandbox-probe", cwd) >= (int)sizeof(granted)) {
        diag("probe working directory is too long");
        return LAUNCHER_FAILURE_EXIT;
    }
    /* Remove a previous probe's residue before confinement makes that impossible. */
    char stale[PATH_MAX];
    if (snprintf(stale, sizeof(stale), "%s/inside", granted) < (int)sizeof(stale)) (void)unlink(stale);
    (void)rmdir(granted);
    if (mkdir(granted, 0700) != 0) {
        diag("probe working directory is not writable (%s)", strerror(errno));
        return LAUNCHER_FAILURE_EXIT;
    }
    uint64_t handled = write_rights(abi);
    int ruleset_fd = create_ruleset(handled);
    if (ruleset_fd < 0 || grant_path(ruleset_fd, granted, handled) != 0 ||
        grant_path(ruleset_fd, "/dev/null", handled) != 0) {
        diag("cannot build the probe ruleset (%s)", strerror(errno));
        if (ruleset_fd >= 0) close(ruleset_fd);
        (void)rmdir(granted);
        return LAUNCHER_FAILURE_EXIT;
    }
    if (enforce(ruleset_fd) != 0) {
        diag("cannot enforce the probe ruleset (%s)", strerror(errno));
        close(ruleset_fd);
        (void)rmdir(granted);
        return LAUNCHER_FAILURE_EXIT;
    }
    close(ruleset_fd);

    /* A write in the granted directory must succeed. */
    char inside[PATH_MAX];
    if (snprintf(inside, sizeof(inside), "%s/inside", granted) >= (int)sizeof(inside)) {
        diag("probe grant path is too long");
        return LAUNCHER_FAILURE_EXIT;
    }
    int inside_fd = open(inside, O_CREAT | O_WRONLY | O_CLOEXEC, 0600);
    if (inside_fd < 0) {
        diag("granted probe directory is not writable (%s)", strerror(errno));
        return LAUNCHER_FAILURE_EXIT;
    }
    close(inside_fd);
    (void)unlink(inside);

    /* An unnamed write in the (denied) parent must fail with a denial error. */
    int escape_fd = open(cwd, O_TMPFILE | O_RDWR | O_CLOEXEC, 0600);
    if (escape_fd >= 0) {
        close(escape_fd);
        diag("the kernel accepted a write outside the granted root; refusing to claim confinement");
        return LAUNCHER_FAILURE_EXIT;
    }
    if (errno != EACCES && errno != EPERM) {
        /* O_TMPFILE can be unsupported; fall back to a named probe file. */
        char escape[PATH_MAX];
        if (snprintf(escape, sizeof(escape), "%s/escape", cwd) >= (int)sizeof(escape)) {
            diag("probe working directory is too long");
            return LAUNCHER_FAILURE_EXIT;
        }
        escape_fd = open(escape, O_CREAT | O_WRONLY | O_CLOEXEC, 0600);
        if (escape_fd >= 0) {
            close(escape_fd);
            diag("the kernel accepted a write outside the granted root; refusing to claim confinement");
            return LAUNCHER_FAILURE_EXIT;
        }
        if (errno != EACCES && errno != EPERM) {
            diag("cannot distinguish a denied write from an I/O error (%s)", strerror(errno));
            return LAUNCHER_FAILURE_EXIT;
        }
    }

    dprintf(STDOUT_FILENO, "mca-dsh-sandbox: landlock abi=%d\n", abi);
    if (abi < 3) {
        /* ABI < 3 cannot mediate truncate(2); write-open is still confined, so
         * most commands are confined but this is not the complete policy. */
        dprintf(STDOUT_FILENO, "mca-dsh-sandbox: partial enforcement (landlock abi=%d)\n", abi);
    }
    return 0;
}

static int add_grant(const char *path, const char **roots, int *is_dir, int *count, int require_dir) {
    struct stat info;
    if (stat(path, &info) != 0) {
        diag("grant root does not exist: %s", path);
        return -1;
    }
    if (require_dir && !S_ISDIR(info.st_mode)) {
        diag("grant root is not a directory: %s", path);
        return -1;
    }
    if (*count >= MAX_GRANTS) {
        diag("too many writable roots in the profile");
        return -1;
    }
    for (int index = 0; index < *count; index++) {
        if (strcmp(roots[index], path) == 0) return 0;
    }
    roots[*count] = path;
    is_dir[*count] = S_ISDIR(info.st_mode);
    (*count)++;
    return 0;
}

static int run_confined(int argc, char **argv) {
    const char *roots[MAX_GRANTS];
    int is_dir[MAX_GRANTS];
    int count = 0;
    int die_with_parent = 0;
    int index = 1;
    int command_index = -1;

    while (index < argc) {
        const char *argument = argv[index];
        if (strcmp(argument, "--") == 0) {
            command_index = index + 1;
            break;
        } else if (strcmp(argument, "--ro-bind") == 0) {
            if (index + 2 >= argc) { diag("truncated --ro-bind"); return LAUNCHER_FAILURE_EXIT; }
            index += 3;
        } else if (strcmp(argument, "--bind") == 0) {
            if (index + 2 >= argc) { diag("truncated --bind"); return LAUNCHER_FAILURE_EXIT; }
            if (add_grant(argv[index + 2], roots, is_dir, &count, 1) != 0) return LAUNCHER_FAILURE_EXIT;
            index += 3;
        } else if (strcmp(argument, "--tmpfs") == 0) {
            if (index + 1 >= argc) { diag("truncated --tmpfs"); return LAUNCHER_FAILURE_EXIT; }
            const char *mount = argv[index + 1];
            const char *temp = getenv("TMPDIR");
            const char *resolved = NULL;
            if (temp != NULL && temp[0] == '/') resolved = temp;
            else if (mount != NULL && mount[0] == '/') resolved = mount;
            if (resolved != NULL) {
                struct stat info;
                if (stat(resolved, &info) == 0 && S_ISDIR(info.st_mode)) {
                    if (add_grant(resolved, roots, is_dir, &count, 1) != 0) return LAUNCHER_FAILURE_EXIT;
                }
            }
            index += 2;
        } else if (strcmp(argument, "--dev") == 0 || strcmp(argument, "--proc") == 0) {
            if (index + 1 >= argc) { diag("truncated %s", argument); return LAUNCHER_FAILURE_EXIT; }
            index += 2;
        } else if (strcmp(argument, "--unshare-pid") == 0) {
            /* No user namespace is available to an Android app; PID visibility is
             * not part of the file-write policy this runner establishes. */
            index += 1;
        } else if (strcmp(argument, "--die-with-parent") == 0) {
            die_with_parent = 1;
            index += 1;
        } else {
            diag("unsupported profile argument: %s", argument);
            return LAUNCHER_FAILURE_EXIT;
        }
    }
    if (command_index < 0 || command_index >= argc) {
        diag("missing command after --");
        return LAUNCHER_FAILURE_EXIT;
    }
    if (die_with_parent) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) != 0) {
            diag("cannot set the parent-death signal (%s)", strerror(errno));
            return LAUNCHER_FAILURE_EXIT;
        }
    }
    int abi = apply_ruleset(write_rights(landlock_abi()), roots, is_dir, count);
    if (abi < 0) return LAUNCHER_FAILURE_EXIT;
    execvp(argv[command_index], &argv[command_index]);
    /* Target resolution/exec failure is not a launcher failure. Keep the
     * diagnostic free of the launcher prefix so settlement classifies it as
     * the command's own failure. */
    dprintf(STDERR_FILENO, "exec %s: %s\n", argv[command_index], strerror(errno));
    return 127;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--probe") == 0) return run_probe();
    return run_confined(argc, argv);
}
