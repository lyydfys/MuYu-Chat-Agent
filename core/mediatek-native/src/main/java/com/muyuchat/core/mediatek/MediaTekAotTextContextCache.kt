package com.muyuchat.core.mediatek

/**
 * Mirrors the reference text cache identity: text weights + tokenizer + lk + prompt.
 * The reference keeps one cached ACTX buffer per text path; MCA keeps a tiny LRU so
 * consecutive generations with the same prompt do not re-read 1.46 GB of weights.
 */
class MediaTekAotTextContextCache(private val capacity: Int = 2) {
    data class Entry(
        val encoding: MediaTekAotTextEncoder.Encoding,
        val truncatedQwen: Boolean,
        val truncatedT5: Boolean,
    )

    private val entries = object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > capacity
    }

    init {
        require(capacity in 1..16) { "capacity must be within 1..16" }
    }

    @Synchronized
    fun get(key: String): Entry? = entries[key]

    @Synchronized
    fun put(key: String, entry: Entry) {
        entries[key] = entry
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}
