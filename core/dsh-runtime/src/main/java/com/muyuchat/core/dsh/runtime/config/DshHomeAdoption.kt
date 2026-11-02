package com.muyuchat.core.dsh.runtime.config

import java.io.File
import org.json.JSONObject

/**
 * Seed marker phase. `SEEDING` is a resumable interrupted seed: the next generation resumes it
 * instead of misreading a half-written first run as data loss.
 */
internal enum class SeedPhase(val value: String) {
    SEEDING("seeding"),
    COMPLETE("complete");

    companion object {
        fun fromValue(value: String): SeedPhase? = entries.firstOrNull { it.value == value }
    }
}

/**
 * Read-only adoption decision for a DSH home.
 *
 * This is the "may this home be seeded, resumed, migrated or is it lost data?" question, split out
 * of [DshGatewayConfigSeeder] because it is a pure inspection: it writes nothing, and every refusal
 * must fire before the model catalog is fetched or any template byte is copied. Keeping it in its
 * own owner lets the data-loss rules be reviewed and tested without the write path in the same file.
 */
internal class DshHomeAdoption(
    private val homeDir: File,
    private val dataRoot: File,
    private val workspaceDir: File,
    private val templateVersion: Int,
    private val legacyTemplateVersion: Int,
    private val markerVersion: Int
) {

    /** What the current on-disk home means for this seed attempt. */
    internal sealed interface Decision {
        /** No home, or an existing but empty home: safe to seed. */
        data object Fresh : Decision

        /** Our own interrupted seed (`phase=seeding`): resume it. */
        data object Resume : Decision

        /** A structurally complete home from an older/unmarked template: migrate by version. */
        data class Upgrade(val fromTemplateVersion: Int) : Decision

        /** A structurally complete home already at the current template version. */
        data object Current : Decision

        data class Refused(val code: DshSeedFailureCode, val detail: String) : Decision
    }

    /**
     * Classifies the home without writing anything. Refusals are returned before the catalog is
     * fetched, so a lost home fails closed even when the loopback gateway is down.
     */
    fun classify(): Decision {
        if (homeDir.exists() && !homeDir.isDirectory) {
            return Decision.Refused(
                DshSeedFailureCode.HOME_NOT_A_DIRECTORY,
                "the DSH home path is not a directory: ${homeDir.absolutePath}"
            )
        }
        val entries = if (homeDir.exists()) {
            val listed = homeDir.listFiles() ?: return Decision.Refused(
                DshSeedFailureCode.HOME_UNREADABLE,
                "the DSH home directory cannot be listed: ${homeDir.absolutePath}"
            )
            // Our own atomic-write temp is never authoritative. A crash during the very first
            // `seeding` marker write can leave only that temp file; treating it as "existing
            // non-empty home" would misclassify a first run as data loss.
            listed.filterNot { it.isFile && it.name.endsWith(DshGatewayConfigSeeder.TEMP_SUFFIX) }.toTypedArray()
        } else {
            emptyArray<File>()
        }
        // The home marker is deleted together with the home. A sibling record directly below
        // `dataRoot` (which survives a home-only deletion/restore) proves this installation was
        // already seeded once, so an absent/empty home is data loss rather than a first run.
        if (entries.isEmpty() && homeStateMarkerFile().exists()) {
            return Decision.Refused(
                DshSeedFailureCode.HOME_INCOMPLETE,
                "the DSH home is missing/empty but a prior successful seed is recorded at " +
                    "${homeStateMarkerFile().absolutePath}; refusing to recreate it"
            )
        }
        // A home that is absent/empty while the sibling user workspace still holds tool/user files
        // is evidence of a lost home, not a first run: the first run has no workspace data yet.
        if (entries.isEmpty() && workspaceHasUserData()) {
            return Decision.Refused(
                DshSeedFailureCode.HOME_INCOMPLETE,
                "the DSH home is missing/empty but ${workspaceDir.absolutePath} still contains " +
                    "user/tool data; refusing to seed a replacement home"
            )
        }
        val markerFile = File(homeDir, DshGatewayConfigSeeder.TEMPLATE_MARKER_NAME)
        if (!markerFile.exists()) {
            if (entries.isEmpty()) return Decision.Fresh
            // A non-empty home with no marker was written by an older MCA build (or by something
            // else). It is adopted only when it is structurally complete; missing required
            // directories are treated as data-loss evidence, never recreated.
            val missingDirs = missingRequiredDirs()
            if (missingDirs.isNotEmpty()) {
                return Decision.Refused(
                    DshSeedFailureCode.HOME_INCOMPLETE,
                    "the existing DSH home is missing ${missingDirs.joinToString(",")}; refusing to recreate it"
                )
            }
            val missingFiles = missingProfileTemplateFiles()
            if (missingFiles.isNotEmpty()) {
                return Decision.Refused(
                    DshSeedFailureCode.HOME_INCOMPLETE,
                    "the existing DSH home is missing config files ${missingFiles.joinToString(",")}; refusing to recreate it"
                )
            }
            return Decision.Upgrade(fromTemplateVersion = legacyTemplateVersion)
        }

        val marker = readMarker(markerFile) ?: return Decision.Refused(
            DshSeedFailureCode.MARKER_CORRUPT,
            "the DSH home template marker cannot be read: ${markerFile.absolutePath}"
        )
        if (marker.templateVersion > templateVersion) {
            return Decision.Refused(
                DshSeedFailureCode.TEMPLATE_VERSION_UNSUPPORTED,
                "the DSH home was seeded by template version ${marker.templateVersion}, " +
                    "but this runtime supports at most $templateVersion"
            )
        }
        if (marker.phase == SeedPhase.SEEDING) return Decision.Resume
        val missingDirs = missingRequiredDirs()
        if (missingDirs.isNotEmpty()) {
            return Decision.Refused(
                DshSeedFailureCode.HOME_INCOMPLETE,
                "the existing DSH home is missing ${missingDirs.joinToString(",")}; refusing to recreate it"
            )
        }
        val missingFiles = missingProfileTemplateFiles()
        if (missingFiles.isNotEmpty()) {
            return Decision.Refused(
                DshSeedFailureCode.HOME_INCOMPLETE,
                "the existing DSH home is missing config files ${missingFiles.joinToString(",")}; refusing to recreate it"
            )
        }
        return if (marker.templateVersion < templateVersion) {
            Decision.Upgrade(marker.templateVersion)
        } else {
            Decision.Current
        }
    }

    private fun missingRequiredDirs(): List<String> =
        DshGatewayConfigSeeder.REQUIRED_HOME_DIRS.filterNot { relative -> File(homeDir, relative).isDirectory }

    /**
     * True when the sibling tool workspace already holds data. An unreadable workspace also
     * counts as evidence: replacing the home in that state could hide data loss.
     */
    private fun workspaceHasUserData(): Boolean {
        if (!workspaceDir.exists()) return false
        val entries = workspaceDir.listFiles() ?: return true
        return entries.isNotEmpty()
    }

    private fun missingProfileTemplateFiles(): List<String> =
        DshGatewayConfigSeeder.PROFILE_TEMPLATE_FILES.filterNot { name -> File(File(homeDir, DshGatewayConfigSeeder.PROFILE_DIR_RELATIVE), name).isFile }

    private fun readMarker(markerFile: File): TemplateMarker? = runCatching {
        val json = JSONObject(markerFile.readText(Charsets.UTF_8))
        val parsedMarkerVersion = json.optInt("markerVersion", -1)
        val parsedTemplateVersion = json.optInt("templateVersion", -1)
        val phase = SeedPhase.fromValue(json.optString("phase"))
        if (parsedMarkerVersion != markerVersion || parsedTemplateVersion < 0 || phase == null) {
            null
        } else {
            TemplateMarker(parsedTemplateVersion, phase)
        }
    }.getOrNull()

    private fun homeStateMarkerFile(): File = File(dataRoot, DshGatewayConfigSeeder.HOME_STATE_MARKER_NAME)

    private data class TemplateMarker(
        val templateVersion: Int,
        val phase: SeedPhase
    )
}
