package com.muyuchat.core.dsh.runtime.payload

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Result of forcing one file or directory entry to stable storage. */
sealed interface DshSyncOutcome {
    data object Synced : DshSyncOutcome

    /**
     * The platform refuses this fsync (some JVM/Android combinations reject directory channels).
     * Recorded in the install notes instead of being hidden or promoted to success.
     */
    data class Unsupported(val detail: String) : DshSyncOutcome

    /** A real write-back failure: the caller must not publish. */
    data class Failed(val detail: String) : DshSyncOutcome
}

/** Narrow durability seam so crash-consistency branches can be tested without power loss. */
interface DshDurability {
    fun syncFile(file: File): DshSyncOutcome
    fun syncDirectory(directory: File): DshSyncOutcome
}

/** Production durability: fsync(2) through the descriptor, directory entries best effort. */
object DefaultDshDurability : DshDurability {
    override fun syncFile(file: File): DshSyncOutcome = try {
        RandomAccessFile(file, "rw").use { handle -> handle.fd.sync() }
        DshSyncOutcome.Synced
    } catch (t: Throwable) {
        DshSyncOutcome.Failed(describeFailure(t))
    }

    override fun syncDirectory(directory: File): DshSyncOutcome = try {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
        DshSyncOutcome.Synced
    } catch (t: Throwable) {
        DshSyncOutcome.Unsupported(describeFailure(t))
    }
}

/**
 * Engineering description of a publish failure.
 *
 * Top level rather than a companion member so the durability seam above and the publisher below
 * share one implementation; [DshDirectoryPublisher.describe] stays as the public entry point.
 */
internal fun describeFailure(t: Throwable): String {
    val reason = (t as? java.nio.file.FileSystemException)?.reason?.takeIf { it.isNotBlank() }
    return buildString {
        append(t.javaClass.simpleName)
        t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        if (reason != null && t.message?.contains(reason) != true) {
            append(" (").append(reason).append(')')
        }
    }
}

/**
 * The file operations the publish protocol depends on, injectable so every failure branch
 * (target appeared, cross-device rename, EACCES) is deterministic in tests.
 */
interface DshFileOps {
    /** mkdir(2): atomically fails with [FileAlreadyExistsException] when anything is there. */
    fun createDirectory(path: Path)

    /** rename(2) with ATOMIC_MOVE; must not be emulated with stat-then-rename. */
    fun atomicMove(source: Path, target: Path)

    fun deleteIfExists(path: Path): Boolean
    fun existsNoFollow(path: Path): Boolean
    fun isDirectoryNoFollow(path: Path): Boolean
}

object DefaultDshFileOps : DshFileOps {
    override fun createDirectory(path: Path) {
        Files.createDirectory(path)
    }

    override fun atomicMove(source: Path, target: Path) {
        Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    override fun deleteIfExists(path: Path): Boolean = Files.deleteIfExists(path)

    override fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    override fun isDirectoryNoFollow(path: Path): Boolean = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
}

/** Publish policy: how much durability work is done before the marker becomes visible. */
data class DshPublishPolicy(
    /** fsync every extracted file before the tree may be published. */
    val fsyncStagedContent: Boolean = true,
    /** fsync the containing directory after the rename / marker link. */
    val fsyncDirectories: Boolean = true
)

/** Outcome of claiming the final target with a no-replace mkdir. */
sealed interface DshTargetClaim {
    data object Claimed : DshTargetClaim
    data class AlreadyPresent(val detail: String) : DshTargetClaim
    data class Failed(val detail: String) : DshTargetClaim
}

/** Outcome of the atomic rename onto the claim. */
sealed interface DshDirectoryPublish {
    data class Published(val notes: List<String>) : DshDirectoryPublish
    data class NotPublished(val detail: String) : DshDirectoryPublish
}

/**
 * Atomic, non-overwriting directory publication.
 *
 * The protocol makes the no-replace guarantee kernel-enforced instead of check-then-rename:
 *
 *  1. `mkdir(target)` — a raw no-replace primitive. If anything is already at the target
 *     (including a symlink or a foreign directory) this fails and nothing is touched;
 *  2. `rename(staged, target, ATOMIC_MOVE)` — replaces only the empty directory this protocol
 *     just created. A foreign non-empty directory makes rename fail (ENOTEMPTY), so user data can
 *     never be silently replaced by a `stat`-then-`rename` race.
 *
 * On hosts whose rename cannot replace an even-empty directory (Win32 hosts used by the JVM unit
 * tests) the empty claim is verified and removed, then the same atomic move is retried; this path
 * is recorded in the notes and never runs on Android/Linux, where rename replaces the claim.
 */
class DshDirectoryPublisher(
    private val ops: DshFileOps = DefaultDshFileOps,
    private val durability: DshDurability = DefaultDshDurability,
    val policy: DshPublishPolicy = DshPublishPolicy()
) {

    fun claimTarget(target: File): DshTargetClaim {
        return try {
            ops.createDirectory(target.toPath())
            DshTargetClaim.Claimed
        } catch (t: FileAlreadyExistsException) {
            DshTargetClaim.AlreadyPresent("target already exists: ${target.absolutePath}")
        } catch (t: Throwable) {
            DshTargetClaim.Failed("cannot claim publish target: " + describe(t))
        }
    }

    fun publishOntoClaim(staged: File, target: File): DshDirectoryPublish {
        return try {
            ops.atomicMove(staged.toPath(), target.toPath())
            DshDirectoryPublish.Published(emptyList())
        } catch (t: AtomicMoveNotSupportedException) {
            DshDirectoryPublish.NotPublished(
                "atomic rename is unsupported between ${staged.absolutePath} and ${target.absolutePath} " +
                    "(cross-device?): " + describe(t)
            )
        } catch (t: Throwable) {
            retryWithoutClaimOnNonReplacingRename(staged, target, t)
        }
    }

    /** Removes the empty claim this protocol created; never deletes a non-empty directory. */
    fun releaseClaim(target: File): Boolean {
        val path = target.toPath()
        if (!ops.existsNoFollow(path)) return true
        if (!ops.isDirectoryNoFollow(path)) return false
        if (!isEmptyDirectory(target)) return false
        return runCatching { ops.deleteIfExists(path) }.getOrDefault(false)
    }

    /** fsync of one staged file; [policy] decides whether it is required. */
    fun syncStagedFile(file: File): DshSyncOutcome =
        if (!policy.fsyncStagedContent) DshSyncOutcome.Synced else durability.syncFile(file)

    fun syncDirectory(directory: File): DshSyncOutcome =
        if (!policy.fsyncDirectories) {
            DshSyncOutcome.Synced
        } else {
            durability.syncDirectory(directory)
        }

    private fun retryWithoutClaimOnNonReplacingRename(
        staged: File,
        target: File,
        failure: Throwable
    ): DshDirectoryPublish {
        // Only the empty directory this protocol just created may be removed here.
        if (!ops.isDirectoryNoFollow(target.toPath()) || !isEmptyDirectory(target)) {
            return DshDirectoryPublish.NotPublished(
                "publish target is not our empty claim; refusing to overwrite: " + describe(failure)
            )
        }
        val released = runCatching { ops.deleteIfExists(target.toPath()) }.getOrDefault(false)
        if (!released) {
            return DshDirectoryPublish.NotPublished(
                "cannot release the empty publish claim: " + describe(failure)
            )
        }
        return try {
            ops.atomicMove(staged.toPath(), target.toPath())
            DshDirectoryPublish.Published(
                listOf("host rename cannot replace a directory; empty claim released and atomic rename retried")
            )
        } catch (t: Throwable) {
            DshDirectoryPublish.NotPublished("atomic rename failed after releasing the claim: " + describe(t))
        }
    }

    private fun isEmptyDirectory(directory: File): Boolean = runCatching {
        Files.newDirectoryStream(directory.toPath()).use { stream -> !stream.iterator().hasNext() }
    }.getOrDefault(false)

    companion object {
        fun describe(t: Throwable): String {
            val reason = (t as? java.nio.file.FileSystemException)?.reason?.takeIf { it.isNotBlank() }
            return buildString {
                append(t.javaClass.simpleName)
                t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                if (reason != null && t.message?.contains(reason) != true) {
                    append(" (").append(reason).append(')')
                }
            }
        }
    }
}
