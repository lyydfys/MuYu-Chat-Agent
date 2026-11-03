package com.muyuchat.feature.dsh.workspace.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate every device channel runs before a shell sees a command.
 *
 * Cases are grouped per family: destructive commands by name, destructive arguments of otherwise
 * allowed commands, shell composition, unknown commands, the read-only whitelist and the safe
 * re-rendering. A rejection must always carry a stable reason code and must never leak the command.
 */
class DshDeviceCommandPolicyTest {

    // region destructive commands by name

    @Test
    fun `filesystem creation on block devices is denied by name`() {
        assertRejected("mkfs.ext4 /dev/block/sda1", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("mkfs.f2fs /dev/block/by-name/userdata", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("mkfs /dev/block/sda", DshDevicePolicyRejection.DENIED_COMMAND)
    }

    @Test
    fun `partitioning tools are denied`() {
        assertRejected("fdisk /dev/block/sda", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("sfdisk --delete /dev/block/sda", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("parted /dev/block/sda print", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("sgdisk -Z /dev/block/sda", DshDevicePolicyRejection.DENIED_COMMAND)
    }

    @Test
    fun `dd is denied even when it only reads`() {
        assertRejected("dd if=/dev/zero of=/dev/block/sda", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("dd if=/dev/block/sda of=/sdcard/dump.bin", DshDevicePolicyRejection.DENIED_COMMAND)
    }

    @Test
    fun `root and system deletion is denied`() {
        assertRejected("rm -rf /", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("rm -rf /*", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("rm -r /system", DshDevicePolicyRejection.DENIED_COMMAND)
    }

    @Test
    fun `boot power and bootloader commands are denied`() {
        assertRejected("reboot", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("reboot recovery", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("shutdown -h now", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("fastboot flash boot boot.img", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("wipe data", DshDevicePolicyRejection.DENIED_COMMAND)
    }

    @Test
    fun `nested privilege escalation is denied`() {
        assertRejected("su -c id", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("su 0 id", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("sudo -u root id", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("nsenter --mount=/proc/1/ns/mnt", DshDevicePolicyRejection.DENIED_COMMAND)
    }

    @Test
    fun `selinux property and mount changes are denied`() {
        assertRejected("setenforce 0", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("setprop ro.debuggable 1", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("setprop persist.sys.usb.config adb", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("mount -o rw,remount /system", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("umount /system", DshDevicePolicyRejection.DENIED_COMMAND)
    }

    @Test
    fun `permission widening of the filesystem root is denied`() {
        assertRejected("chmod 777 /", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("chown root:root /system", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("chcon u:object_r:system_file:s0 /system", DshDevicePolicyRejection.DENIED_COMMAND)
    }

    @Test
    fun `shell wrappers that would hide a command are denied`() {
        assertRejected("sh -c id", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("busybox id", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("toybox rm -rf /", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("env id", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("eval id", DshDevicePolicyRejection.DENIED_COMMAND)
    }

    // endregion

    // region destructive arguments of otherwise allowed commands

    @Test
    fun `pm is only allowed for the read-only list and path subcommands`() {
        assertAllowed("pm list packages")
        assertAllowed("pm list packages -3")
        assertAllowed("pm path com.android.settings")
        assertRejected("pm uninstall --user 0 com.android.settings", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("pm uninstall com.android.settings", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("pm clear com.android.settings", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("pm disable com.android.settings", DshDevicePolicyRejection.DENIED_ARGUMENT)
    }

    @Test
    fun `wm is read-only only`() {
        assertAllowed("wm size")
        assertAllowed("wm density")
        assertRejected("wm size 1080x1920", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("wm density 320", DshDevicePolicyRejection.DENIED_ARGUMENT)
    }

    @Test
    fun `settings writes are refused and reads are allowed`() {
        assertAllowed("settings get global airplane_mode_on")
        assertAllowed("settings list secure")
        assertRejected("settings put global adb_enabled 1", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("settings delete global adb_enabled", DshDevicePolicyRejection.DENIED_ARGUMENT)
    }

    @Test
    fun `ls rejects recursive listing and traversal paths`() {
        assertAllowed("ls /proc")
        assertAllowed("ls -la /sys/class")
        assertRejected("ls -R /", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("ls /proc/../data", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("ls /sdcard/*", DshDevicePolicyRejection.DENIED_ARGUMENT)
    }

    @Test
    fun `cat only reads kernel and pseudo filesystems`() {
        assertAllowed("cat /proc/version")
        assertAllowed("cat /sys/class/power_supply/battery/capacity")
        assertRejected("cat /etc/hosts", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("cat /dev/block/sda", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("cat /proc/kcore", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("cat /data/data/com.muyuchat.mca/databases/app.db", DshDevicePolicyRejection.DENIED_ARGUMENT)
    }

    @Test
    fun `argument shapes are checked for the remaining allowlist`() {
        assertAllowed("id")
        assertAllowed("id -u")
        assertAllowed("getprop")
        assertAllowed("getprop ro.build.version.sdk")
        assertAllowed("df -h")
        assertAllowed("free -m")
        assertAllowed("ps -A")
        assertAllowed("uname -a")
        assertAllowed("uptime")
        assertAllowed("pwd")
        assertAllowed("whoami")
        assertAllowed("date +%s")
        assertAllowed("getenforce")
        assertRejected("id -x", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("getprop a b", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("getprop \"ro.build.version.sdk\" \"ro.product.model\"", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("df /data", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("ps aux", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("uname --all", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("uptime -p", DshDevicePolicyRejection.DENIED_ARGUMENT)
        assertRejected("date +%F", DshDevicePolicyRejection.DENIED_ARGUMENT)
    }

    // endregion

    // region shell composition

    @Test
    fun `every segment of a composed command is validated`() {
        assertRejected("id; reboot", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("id && rm -rf /", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("id || su -c id", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("id | dd of=/dev/block/sda", DshDevicePolicyRejection.DENIED_COMMAND)
        assertRejected("getprop ro.product.model\nmkfs.ext4 /dev/block/sda1", DshDevicePolicyRejection.DENIED_COMMAND)
    }

    @Test
    fun `a fully whitelisted pipeline is allowed and keeps its separators`() {
        val decision = DshDeviceCommandPolicy.evaluate("id && getprop ro.product.model")
        val allowed = decision as DshDeviceCommandDecision.Allowed
        assertEquals(2, allowed.segments.size)
        assertEquals(DshDeviceSegmentSeparator.FIRST, allowed.segments[0].separator)
        assertEquals(DshDeviceSegmentSeparator.AND, allowed.segments[1].separator)
        assertEquals(listOf("id"), allowed.segments[0].argv)
        assertEquals(listOf("getprop", "ro.product.model"), allowed.segments[1].argv)
    }

    @Test
    fun `command substitution backticks and variables are refused`() {
        assertRejected("cat \$(whoami)", DshDevicePolicyRejection.SHELL_COMPOSITION)
        assertRejected("cat `id`", DshDevicePolicyRejection.SHELL_COMPOSITION)
        assertRejected("getprop \$ROOT", DshDevicePolicyRejection.SHELL_COMPOSITION)
        assertRejected("getprop \"\$ROOT\"", DshDevicePolicyRejection.SHELL_COMPOSITION)
    }

    @Test
    fun `redirection subshells braces and background execution are refused`() {
        assertRejected("id > /sdcard/out.txt", DshDevicePolicyRejection.SHELL_COMPOSITION)
        assertRejected("id >> /sdcard/out.txt", DshDevicePolicyRejection.SHELL_COMPOSITION)
        assertRejected("id < /proc/version", DshDevicePolicyRejection.SHELL_COMPOSITION)
        assertRejected("(id)", DshDevicePolicyRejection.SHELL_COMPOSITION)
        assertRejected("id &", DshDevicePolicyRejection.SHELL_COMPOSITION)
        assertRejected("id # comment", DshDevicePolicyRejection.SHELL_COMPOSITION)
    }

    @Test
    fun `quoting cannot smuggle an operator into a validated argument`() {
        // The quoted ';' is data, but 'cat' only accepts diagnostics paths, so this is refused.
        assertRejected("cat '/proc/version;id'", DshDevicePolicyRejection.DENIED_ARGUMENT)
        // A quoted double-quoted literal argument is accepted (no expansion inside).
        assertAllowed("getprop \"ro.product.model\"")
        assertAllowed("ls \"/proc\"")
    }

    @Test
    fun `empty and repeated separators are refused`() {
        assertRejected(";id", DshDevicePolicyRejection.EMPTY_SEGMENT)
        assertRejected("id;;ls", DshDevicePolicyRejection.EMPTY_SEGMENT)
        assertRejected("id &&", DshDevicePolicyRejection.EMPTY_SEGMENT)
        assertRejected("id |", DshDevicePolicyRejection.EMPTY_SEGMENT)
        // A single trailing ';' is a legal shell terminator and stays allowed.
        assertAllowed("id;")
    }

    // endregion

    // region unknown commands

    @Test
    fun `unknown commands are never forwarded`() {
        assertRejected("dumpsys window", DshDevicePolicyRejection.NOT_WHITELISTED)
        assertRejected("magisk --status", DshDevicePolicyRejection.NOT_WHITELISTED)
        assertRejected("getevent -l", DshDevicePolicyRejection.NOT_WHITELISTED)
        assertRejected("ip addr", DshDevicePolicyRejection.NOT_WHITELISTED)
    }

    @Test
    fun `a command path instead of a bare name is refused`() {
        assertRejected("/system/bin/id", DshDevicePolicyRejection.NOT_WHITELISTED)
        assertRejected("./id", DshDevicePolicyRejection.NOT_WHITELISTED)
    }

    @Test
    fun `empty commands are refused`() {
        assertRejected("", DshDevicePolicyRejection.EMPTY_COMMAND)
        assertRejected("   ", DshDevicePolicyRejection.EMPTY_COMMAND)
    }

    @Test
    fun `over long commands are refused`() {
        val long = "getprop " + "a".repeat(DshDeviceCommandPolicy.MAX_COMMAND_LENGTH)
        assertRejected(long, DshDevicePolicyRejection.COMMAND_TOO_LONG)
    }

    @Test
    fun `reason codes are stable lower snake case`() {
        DshDevicePolicyRejection.entries.forEach { rejection ->
            assertTrue(
                "unexpected code shape: ${rejection.code}",
                Regex("^[a-z][a-z0-9_]*$").matches(rejection.code)
            )
        }
    }

    // endregion

    // region safe rendering

    @Test
    fun `safe rendering quotes every argument`() {
        val segments = listOf(
            DshDeviceCommandSegment(0, DshDeviceSegmentSeparator.FIRST, listOf("cat", "/proc/x;rm -rf /")),
            DshDeviceCommandSegment(1, DshDeviceSegmentSeparator.AND, listOf("id"))
        )
        assertEquals("cat '/proc/x;rm -rf /' && id", DshDeviceCommandPolicy.renderSafe(segments))
    }

    @Test
    fun `shell quoting escapes single quotes and empty arguments`() {
        assertEquals("id", DshDeviceCommandPolicy.shellQuote("id"))
        assertEquals("ro.product.model", DshDeviceCommandPolicy.shellQuote("ro.product.model"))
        assertEquals("''", DshDeviceCommandPolicy.shellQuote(""))
        assertEquals("'a'\\''b'", DshDeviceCommandPolicy.shellQuote("a'b"))
    }

    @Test
    fun `an allowed command renders back to the same argv`() {
        val decision = DshDeviceCommandPolicy.evaluate("getprop \"ro.product.model\"")
        val allowed = decision as DshDeviceCommandDecision.Allowed
        assertEquals("getprop ro.product.model", DshDeviceCommandPolicy.renderSafe(allowed.segments))
    }

    @Test
    fun `rejection never echoes the submitted command`() {
        val command = "rm -rf /sdcard/private-session-name"
        val decision = DshDeviceCommandPolicy.evaluate(command)
        val rejected = decision as DshDeviceCommandDecision.Rejected
        assertFalse(rejected.rule.contains("private-session-name"))
        assertFalse(rejected.rule.contains("rm -rf"))
        assertEquals("denied_command", rejected.rejection.code)
    }

    // endregion

    private fun assertAllowed(command: String) {
        val decision = DshDeviceCommandPolicy.evaluate(command)
        assertTrue(
            "expected <$command> to be allowed but it was $decision",
            decision is DshDeviceCommandDecision.Allowed
        )
    }

    private fun assertRejected(command: String, expected: DshDevicePolicyRejection) {
        val decision = DshDeviceCommandPolicy.evaluate(command)
        assertTrue(
            "expected <$command> to be rejected but it was $decision",
            decision is DshDeviceCommandDecision.Rejected
        )
        assertEquals("<$command>", expected.code, (decision as DshDeviceCommandDecision.Rejected).rejection.code)
    }
}
