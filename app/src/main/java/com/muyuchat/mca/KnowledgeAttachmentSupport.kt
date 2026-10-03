package com.muyuchat.mca

import android.content.ContentResolver
import android.net.Uri

/**
 * Whether a picker result is a text-like attachment the knowledge base can ingest.
 *
 * **One function, one Android dependency, no port.** The only thing it needed from the ViewModel was a
 * `ContentResolver`, so it takes one as a parameter. The list of extensions is a user-visible contract:
 * it is exactly what the "knowledge base supports text, Markdown, JSON, XML, CSV and code files"
 * message promises, so the list is reproduced verbatim, entry for entry.
 */
internal fun isSupportedTextAttachment(
    resolver: ContentResolver,
    uri: Uri,
    name: String
): Boolean {
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val lowerName = name.lowercase()
        return mime.startsWith("text/") ||
            mime in setOf(
                "application/json", "application/xml", "application/x-ndjson",
                "application/yaml", "application/x-yaml", "text/yaml",
                "text/csv", "application/rtf"
            ) ||
            lowerName.endsWith(".txt") ||
            lowerName.endsWith(".md") ||
            lowerName.endsWith(".markdown") ||
            lowerName.endsWith(".json") ||
            lowerName.endsWith(".json5") ||
            lowerName.endsWith(".jsonl") ||
            lowerName.endsWith(".xml") ||
            lowerName.endsWith(".yaml") ||
            lowerName.endsWith(".yml") ||
            lowerName.endsWith(".csv") ||
            lowerName.endsWith(".log") ||
            lowerName.endsWith(".html") ||
            lowerName.endsWith(".htm") ||
            lowerName.endsWith(".rst") ||
            lowerName.endsWith(".tex") ||
            lowerName.endsWith(".srt") ||
            lowerName.endsWith(".vtt") ||
            lowerName.endsWith(".toml") ||
            lowerName.endsWith(".ini") ||
            lowerName.endsWith(".conf") ||
            lowerName.endsWith(".cfg") ||
            lowerName.endsWith(".sql") ||
            lowerName.endsWith(".sh") ||
            lowerName.endsWith(".bash") ||
            lowerName.endsWith(".zsh") ||
            lowerName.endsWith(".bat") ||
            lowerName.endsWith(".cmd") ||
            lowerName.endsWith(".ps1") ||
            lowerName.endsWith(".c") ||
            lowerName.endsWith(".h") ||
            lowerName.endsWith(".cc") ||
            lowerName.endsWith(".cpp") ||
            lowerName.endsWith(".cxx") ||
            lowerName.endsWith(".hpp") ||
            lowerName.endsWith(".cs") ||
            lowerName.endsWith(".go") ||
            lowerName.endsWith(".rs") ||
            lowerName.endsWith(".swift") ||
            lowerName.endsWith(".rb") ||
            lowerName.endsWith(".php") ||
            lowerName.endsWith(".dart") ||
            lowerName.endsWith(".vue") ||
            lowerName.endsWith(".jsx") ||
            lowerName.endsWith(".tsx") ||
            lowerName.endsWith(".gradle") ||
            lowerName.endsWith(".properties") ||
            lowerName.endsWith(".proto") ||
            lowerName.endsWith(".kt") ||
            lowerName.endsWith(".java") ||
            lowerName.endsWith(".py") ||
            lowerName.endsWith(".js") ||
            lowerName.endsWith(".ts")
}
