package com.muyuchat.mca

import java.io.File

/**
 * Finds a file by name anywhere beneath this directory, case-insensitively.
 *
 * One implementation, in one place. The same function existed twice in this package, private and
 * byte-identical, in LocalImageProvider and LocalVisionBundle. Both look for a required binary
 * inside an extracted model bundle -- a QNN context file, a vision projector -- so a divergence
 * would mean a bundle that one path accepts and the other reports as incomplete.
 *
 * Kept in this package: both callers are here, so no import, dependency or visibility question
 * arises.
 *
 * Semantics, unchanged from both copies:
 *   - the search is depth-first over the whole tree
 *   - the name comparison ignores case, because the same file is shipped with different casing
 *   - the first match wins, and no match yields null rather than throwing
 */
internal fun File.findDescendantFile(fileName: String): File? =
    walkTopDown().firstOrNull { it.isFile && it.name.equals(fileName, ignoreCase = true) }
