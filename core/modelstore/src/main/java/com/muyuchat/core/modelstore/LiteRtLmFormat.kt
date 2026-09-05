package com.muyuchat.core.modelstore

import java.io.File

/**
 * Bounded validation for the LiteRT-LM container.
 *
 * A LiteRT-LM file starts with a 32-byte little-endian preamble followed by a
 * FlatBuffers metadata table. The native loader maps the ranges advertised by
 * that table, so checking only the magic and header offset is insufficient: a
 * truncated download can otherwise pass the model-store check and fail later
 * with an mmap "length/offset too large" error. This verifier intentionally
 * understands only the stable header schema and never reads model payloads.
 */
internal const val LITERT_LM_MAGIC = "LITERTLM"
internal val LITERT_LM_MAGIC_BYTES: ByteArray = LITERT_LM_MAGIC.toByteArray(Charsets.US_ASCII)
internal const val LITERT_LM_MAGIC_SIZE: Int = 8
internal const val LITERT_LM_FIXED_HEADER_SIZE: Int = 32

private const val LITERT_LM_SUPPORTED_MAJOR_VERSION: Long = 1L
private const val LITERT_LM_HEADER_MAX_SIZE: Long = 16L * 1024L
private const val FLATBUFFER_MAX_VECTOR_ELEMENTS: Long = 1L shl 20
private const val LITERT_LM_TFLITE_MODEL_SECTION_TYPE: Int = 3
private const val LITERT_LM_STRING_VALUE_UNION_TYPE: Int = 9
private const val LITERT_LM_MODEL_TYPE_KEY: String = "model_type"
private const val LITERT_LM_TEXT_MODEL_TYPE: String = "TF_LITE_PREFILL_DECODE"
// GPU Artisan packages use a different, but still text-decoder, section name.
// It is emitted by the official LiteRT-LM GPU artifact as
// `tf_lite_artisan_text_decoder`; accepting this exact value keeps the
// preflight strict while avoiding a false rejection of a runnable GPU model.
private const val LITERT_LM_ARTISAN_TEXT_MODEL_TYPE: String = "TF_LITE_ARTISAN_TEXT_DECODER"

internal fun ByteArray.hasLiteRtLmMagic(): Boolean =
    size >= LITERT_LM_MAGIC_SIZE &&
        indices.take(LITERT_LM_MAGIC_SIZE).all { index -> this[index] == LITERT_LM_MAGIC_BYTES[index] }

internal fun File.hasLiteRtLmMagic(): Boolean {
    if (!isFile || !canRead() || length() < LITERT_LM_MAGIC_SIZE) return false
    return runCatching {
        inputStream().use { it.readPrefix(LITERT_LM_MAGIC_SIZE).hasLiteRtLmMagic() }
    }.getOrDefault(false)
}

/**
 * Validates the preamble, FlatBuffers metadata graph, and all section ranges.
 * [expectedSizeBytes] is the provider/manifest size and is deliberately
 * compared with the observed file size before any metadata is trusted.
 */
fun validateLiteRtLmLoadPreflight(
    file: File,
    expectedSizeBytes: Long
): ModelCompatibilityResult {
    val actualSize = runCatching { file.length() }.getOrDefault(-1L)
    when {
        !file.exists() -> return rejected("LiteRT-LM 模型不存在", file.absolutePath)
        !file.isFile -> return rejected("LiteRT-LM 路径不是普通文件", file.absolutePath)
        !file.canRead() -> return rejected("LiteRT-LM 模型不可读", file.absolutePath)
        actualSize <= 0L -> return rejected("LiteRT-LM 模型为空", file.absolutePath)
        expectedSizeBytes <= 0L || actualSize != expectedSizeBytes -> {
            return rejected("LiteRT-LM 模型大小不一致", "expected=$expectedSizeBytes, actual=$actualSize")
        }
        actualSize < LITERT_LM_FIXED_HEADER_SIZE -> {
            return rejected("LiteRT-LM 文件头不完整", "文件只有 $actualSize 字节，至少需要 $LITERT_LM_FIXED_HEADER_SIZE 字节")
        }
    }

    val result = runCatching { readAndVerifyHeader(file, actualSize) }
        .getOrElse { error ->
            return rejected(
                "LiteRT-LM 文件头/结构无效",
                error.message?.takeIf { it.isNotBlank() } ?: "FlatBuffers metadata 校验失败"
            )
        }

    return ModelCompatibilityResult(
        canLoad = true,
        title = "LiteRT-LM 模型预检通过",
        details = "runtime=${ChatModelRuntime.LITERT_LM.storageValue}, size=$actualSize, " +
            "headerEnd=${result.headerEnd}, sections=${result.sectionCount}"
    )
}

/**
 * Returns true only for a structurally valid LiteRT-LM chat container.
 *
 * Model recovery and the model list must use the same contract as a real chat
 * load.  Checking only the magic header would surface truncated accelerator
 * packages (or non-chat embedder containers) and defer the useful error until
 * the user presses Send.
 */
internal fun isLiteRtLmFile(file: File): Boolean =
    runCatching {
        val size = file.length()
        if (!file.isFile || !file.canRead() || size < LITERT_LM_FIXED_HEADER_SIZE) {
            false
        } else {
            readAndVerifyHeader(file, size, requireTextModel = true)
            true
        }
    }.getOrDefault(false)

private data class HeaderVerification(
    val headerEnd: Long,
    val sectionCount: Int,
    val modelTypes: Set<String>
)

private data class MetadataVerification(
    val sectionCount: Int,
    val modelTypes: Set<String>
)

private data class VerifiedKeyValue(
    val key: String,
    val stringValue: String?
)

private fun rejected(title: String, details: String): ModelCompatibilityResult =
    ModelCompatibilityResult(canLoad = false, title = title, details = details)

private fun readAndVerifyHeader(
    file: File,
    fileSize: Long,
    requireTextModel: Boolean = true
): HeaderVerification {
    val headerPrefix = file.inputStream().use { it.readPrefix(LITERT_LM_FIXED_HEADER_SIZE) }
    require(headerPrefix.size == LITERT_LM_FIXED_HEADER_SIZE) {
        "无法读取完整 LiteRT-LM preamble"
    }
    require(headerPrefix.hasLiteRtLmMagic()) {
        "文件不是有效的 $LITERT_LM_MAGIC 容器，可能是下载错误页、损坏文件或其他模型格式"
    }
    val major = headerPrefix.readLittleEndianUInt32(8)
    require(major == LITERT_LM_SUPPORTED_MAJOR_VERSION) {
        "不支持 LiteRT-LM major version $major"
    }
    val headerEnd = headerPrefix.readLittleEndianUInt64(24)
    require(headerEnd >= LITERT_LM_FIXED_HEADER_SIZE.toLong()) {
        "metadata header offset ($headerEnd) 小于 preamble 大小"
    }
    require(headerEnd <= LITERT_LM_HEADER_MAX_SIZE) {
        "metadata header offset ($headerEnd) 超过 ${LITERT_LM_HEADER_MAX_SIZE} 字节上限"
    }
    require(headerEnd <= fileSize) {
        "metadata header offset ($headerEnd) 超出文件大小 ($fileSize)，文件可能被截断"
    }
    require(headerEnd <= Int.MAX_VALUE.toLong()) { "metadata header 太大" }

    val header = file.inputStream().use { it.readPrefix(headerEnd.toInt()) }
    require(header.size.toLong() == headerEnd) {
        "metadata header 读取不完整：expected=$headerEnd, actual=${header.size}"
    }
    val metadata = LiteRtLmFlatBufferVerifier(
        bytes = header.copyOfRange(LITERT_LM_FIXED_HEADER_SIZE, header.size),
        fileSize = fileSize
    )
    val verified = metadata.verify()
    if (requireTextModel) {
        require(
            verified.modelTypes.any(::isSupportedTextModelType)
        ) {
            "$LITERT_LM_TEXT_MODEL_TYPE/$LITERT_LM_ARTISAN_TEXT_MODEL_TYPE not found in the model"
        }
    }
    return HeaderVerification(
        headerEnd = headerEnd,
        sectionCount = verified.sectionCount,
        modelTypes = verified.modelTypes
    )
}

private fun isSupportedTextModelType(value: String): Boolean =
    value.equals(LITERT_LM_TEXT_MODEL_TYPE, ignoreCase = true) ||
        value.equals(LITERT_LM_ARTISAN_TEXT_MODEL_TYPE, ignoreCase = true)

/** A small, allocation-bounded verifier for the generated LiteRTLMMetaData schema. */
private class LiteRtLmFlatBufferVerifier(
    private val bytes: ByteArray,
    private val fileSize: Long
) {
    private data class Table(
        val start: Int,
        val vtableStart: Int,
        val vtableSize: Int,
        val objectSize: Int
    )

    private data class SectionRange(val begin: Long, val end: Long, val index: Int)

    private val visitedTables = HashSet<Int>()
    private var tableCount = 0

    fun verify(): MetadataVerification {
        require(bytes.size >= 4) { "FlatBuffers metadata 缺少 root offset" }
        val root = tableFromOffset(0, required = true)
        val sectionMetadataSlot = requireNotNull(fieldSlot(root, 1, width = 4, required = true))
        val sectionMetadata = tableFromOffset(sectionMetadataSlot, required = true)
        val objectsSlot = requireNotNull(fieldSlot(sectionMetadata, 0, width = 4, required = true))
        val objects = tableVector(objectsSlot, required = true)
        require(objects.isNotEmpty()) { "LiteRT-LM metadata 没有任何 section" }

        // SystemMetadata is optional in the schema, but if present all of its
        // pointers are verified because the native loader may inspect entries.
        fieldSlot(root, 0, width = 4, required = false)?.let { slot ->
            val system = tableFromOffset(slot, required = true)
            fieldSlot(system, 0, width = 4, required = true)?.let { entriesSlot ->
                tableVector(entriesSlot, required = true).forEach(::verifyKeyValuePair)
            }
        }

        val ranges = ArrayList<SectionRange>(objects.size)
        val modelTypes = LinkedHashSet<String>()
        objects.forEachIndexed { index, section ->
            val items = fieldSlot(section, 0, width = 4, required = false)?.let { itemsSlot ->
                tableVector(itemsSlot, required = true).map(::verifyKeyValuePair)
            }.orEmpty()
            val beginSlot = requireNotNull(fieldSlot(section, 1, width = 8, required = true))
            val endSlot = requireNotNull(fieldSlot(section, 2, width = 8, required = true))
            val begin = readUInt64(beginSlot)
            val end = readUInt64(endSlot)
            val typeSlot = requireNotNull(fieldSlot(section, 3, width = 1, required = true))
            val type = readUInt8(typeSlot)
            require(type in 1..9) { "section[$index] 使用未知 data_type=$type" }
            if (type == LITERT_LM_TFLITE_MODEL_SECTION_TYPE) {
                val modelType = items.firstOrNull {
                    it.key.equals(LITERT_LM_MODEL_TYPE_KEY, ignoreCase = true)
                }?.stringValue?.trim().orEmpty()
                require(modelType.isNotEmpty()) {
                    "section[$index] TFLiteModel 缺少字符串元数据 $LITERT_LM_MODEL_TYPE_KEY"
                }
                modelTypes += modelType
            }
            require(begin >= LITERT_LM_FIXED_HEADER_SIZE) {
                "section[$index] begin_offset=$begin 小于文件头"
            }
            require(begin < end) { "section[$index] 的 begin_offset=$begin 不小于 end_offset=$end" }
            require(end <= fileSize) {
                "section[$index] end_offset=$end 超出文件大小=$fileSize，模型包可能被截断"
            }
            ranges += SectionRange(begin, end, index)
        }
        ranges.sortedBy { it.begin }.zipWithNext().forEach { (left, right) ->
            require(left.end <= right.begin) {
                "section[${right.index}] 与 section[${left.index}] 数据范围重叠"
            }
        }
        return MetadataVerification(
            sectionCount = objects.size,
            modelTypes = modelTypes
        )
    }

    private fun verifyKeyValuePair(table: Table): VerifiedKeyValue {
        val keySlot = requireNotNull(fieldSlot(table, 0, width = 4, required = true))
        val key = readString(keySlot)
        // A FlatBuffers union is serialized as <field>_type followed by the
        // table offset. For KeyValuePair those are vtable fields 1 and 2.
        val typeSlot = requireNotNull(fieldSlot(table, 1, width = 1, required = true))
        val type = readUInt8(typeSlot)
        require(type in 1..12) { "metadata value 使用未知 union type=$type" }
        val valueSlot = requireNotNull(fieldSlot(table, 2, width = 4, required = true))
        val value = tableFromOffset(valueSlot, required = true)
        val stringValue = if (type == LITERT_LM_STRING_VALUE_UNION_TYPE) {
            val stringSlot = requireNotNull(fieldSlot(value, 0, width = 4, required = true))
            readString(stringSlot)
        } else {
            null
        }
        return VerifiedKeyValue(key = key, stringValue = stringValue)
    }

    private fun readString(slot: Int): String {
        val stringStart = requireNotNull(offsetTarget(slot, required = true))
        val length = readUInt32(stringStart).toIntChecked("string length")
        val end = checkedAdd(stringStart, 4L + length, "string end")
        require(end < bytes.size) { "metadata string exceeds FlatBuffer boundary" }
        require(bytes[end.toInt()] == 0.toByte()) { "metadata string is not NUL terminated" }
        return bytes.copyOfRange(stringStart + 4, end.toInt()).toString(Charsets.UTF_8)
    }

    private fun tableVector(slot: Int, required: Boolean): List<Table> {
        val vectorStart = offsetTarget(slot, required)
        if (vectorStart == null) return emptyList()
        val count = readUInt32(vectorStart)
        require(count <= FLATBUFFER_MAX_VECTOR_ELEMENTS) { "FlatBuffer vector 元素数量过大：$count" }
        val countInt = count.toIntChecked("vector length")
        val dataStart = checkedAdd(vectorStart, 4L, "vector data start")
        val dataEnd = checkedAdd(dataStart, count * 4L, "vector data end")
        require(dataEnd <= bytes.size) { "FlatBuffer table vector 超出 metadata 边界" }
        return List(countInt) { index ->
            tableFromOffset((dataStart + index * 4L).toInt(), required = true)
        }
    }

    private fun tableFromOffset(slot: Int, required: Boolean): Table {
        val target = offsetTarget(slot, required)
            ?: throw IllegalArgumentException("required FlatBuffer table pointer is null")
        return tableAt(target)
    }

    private fun offsetTarget(slot: Int, required: Boolean): Int? {
        requireRange(slot, 4, "offset")
        val raw = readUInt32(slot)
        if (raw == 0L) {
            require(!required) { "required FlatBuffer offset is null" }
            return null
        }
        val target = checkedAdd(slot, raw, "offset target")
        require(target < bytes.size) { "FlatBuffer offset target $target 超出 metadata 边界" }
        return target.toInt()
    }

    private fun tableAt(start: Int): Table {
        requireRange(start, 4, "table")
        val signedVtableOffset = readInt32(start).toLong()
        require(signedVtableOffset != 0L) { "FlatBuffer table 的 vtable offset 无效" }
        val vtableLong = start.toLong() - signedVtableOffset
        require(vtableLong >= 0L && vtableLong <= Int.MAX_VALUE) { "FlatBuffer vtable 指针越界" }
        val vtable = vtableLong.toInt()
        requireRange(vtable, 4, "vtable")
        val vtableSize = readUInt16(vtable)
        val objectSize = readUInt16(vtable + 2)
        require(vtableSize >= 4 && vtableSize % 2 == 0) { "FlatBuffer vtable 大小无效：$vtableSize" }
        require(objectSize >= 4) { "FlatBuffer table 大小无效：$objectSize" }
        require(vtableLong + vtableSize <= bytes.size) { "FlatBuffer vtable 超出 metadata 边界" }
        require(start.toLong() + objectSize <= bytes.size) { "FlatBuffer table 超出 metadata 边界" }
        require(vtableSize <= objectSize + 2) { "FlatBuffer vtable/table 大小关系无效" }
        if (visitedTables.add(start)) {
            tableCount += 1
            require(tableCount <= FLATBUFFER_MAX_VECTOR_ELEMENTS) { "FlatBuffer table 数量过大" }
        }
        return Table(start, vtable, vtableSize, objectSize)
    }

    private fun fieldSlot(table: Table, fieldIndex: Int, width: Int, required: Boolean): Int? {
        val entry = 4 + fieldIndex * 2
        if (entry + 2 > table.vtableSize) {
            require(!required) { "required FlatBuffer field[$fieldIndex] 缺失" }
            return null
        }
        val offset = readUInt16(table.vtableStart + entry)
        if (offset == 0) {
            require(!required) { "required FlatBuffer field[$fieldIndex] 缺失" }
            return null
        }
        require(offset >= 4) { "FlatBuffer field[$fieldIndex] 指向 vtable 区域" }
        require(offset.toLong() + width <= table.objectSize) {
            "FlatBuffer field[$fieldIndex] 超出 table 边界"
        }
        val slot = table.start + offset
        requireRange(slot, width, "field[$fieldIndex]")
        return slot
    }

    private fun readUInt8(slot: Int): Int {
        requireRange(slot, 1, "uint8")
        return bytes[slot].toInt() and 0xff
    }

    private fun readUInt16(slot: Int): Int {
        requireRange(slot, 2, "uint16")
        return (bytes[slot].toInt() and 0xff) or ((bytes[slot + 1].toInt() and 0xff) shl 8)
    }

    private fun readInt32(slot: Int): Int {
        requireRange(slot, 4, "int32")
        return (bytes[slot].toInt() and 0xff) or
            ((bytes[slot + 1].toInt() and 0xff) shl 8) or
            ((bytes[slot + 2].toInt() and 0xff) shl 16) or
            (bytes[slot + 3].toInt() shl 24)
    }

    private fun readUInt32(slot: Int): Long {
        requireRange(slot, 4, "uint32")
        return (bytes[slot].toLong() and 0xffL) or
            ((bytes[slot + 1].toLong() and 0xffL) shl 8) or
            ((bytes[slot + 2].toLong() and 0xffL) shl 16) or
            ((bytes[slot + 3].toLong() and 0xffL) shl 24)
    }

    private fun readUInt64(slot: Int): Long {
        requireRange(slot, 8, "uint64")
        var value = 0L
        repeat(8) { index ->
            value = value or ((bytes[slot + index].toLong() and 0xffL) shl (index * 8))
        }
        require(value >= 0L) { "uint64 offset 超过 Long 范围" }
        return value
    }

    private fun requireRange(start: Int, length: Int, label: String) {
        require(start >= 0 && length >= 0 && start.toLong() + length <= bytes.size) {
            "$label 读取越过 metadata 边界"
        }
    }

    private fun checkedAdd(left: Int, right: Long, label: String): Long =
        checkedAdd(left.toLong(), right, label)

    private fun checkedAdd(left: Long, right: Long, label: String): Long {
        require(left >= 0L && right >= 0L && left <= Long.MAX_VALUE - right) { "$label 溢出" }
        return left + right
    }

    private fun Long.toIntChecked(label: String): Int {
        require(this <= Int.MAX_VALUE) { "$label 过大：$this" }
        return toInt()
    }
}

private fun ByteArray.readLittleEndianUInt32(offset: Int): Long {
    require(offset >= 0 && offset + 4 <= size)
    return (this[offset].toLong() and 0xffL) or
        ((this[offset + 1].toLong() and 0xffL) shl 8) or
        ((this[offset + 2].toLong() and 0xffL) shl 16) or
        ((this[offset + 3].toLong() and 0xffL) shl 24)
}

private fun ByteArray.readLittleEndianUInt64(offset: Int): Long {
    require(offset >= 0 && offset + 8 <= size)
    var value = 0L
    repeat(8) { index ->
        value = value or ((this[offset + index].toLong() and 0xffL) shl (index * 8))
    }
    require(value >= 0L) { "LiteRT-LM offset 超过 Long 范围" }
    return value
}
