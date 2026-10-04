package com.muyuchat.mca

import java.security.MessageDigest

/**
 * The fixed graph-aligned QNN UltraFix tile plan and its canonical descriptor digest.
 *
 * Split out of LocalImageInputContract.kt; declarations moved verbatim.
 */

/**
 * Rebuilds the fixed graph-aligned QNN tile plan descriptor. QNN UltraFix uses an 8x latent
 * spatial scale and an 8-latent-origin alignment for both the shared and isolated topologies.
 */
internal data class LocalQnnUltraFixTilePlanEvidence(
    val tileCount: Long,
    val tilePlanSha256: String
)

internal fun localQnnUltraFixTilePlanEvidence(
    request: LocalImageUltraFixOptions
): LocalQnnUltraFixTilePlanEvidence {
    val scale = 8
    val alignment = 8
    require(request.targetWidth >= request.tileSize && request.targetHeight >= request.tileSize) {
        "QNN UltraFix target dimensions must cover the fixed tile canvas."
    }
    require(request.targetWidth % (scale * alignment) == 0 &&
        request.targetHeight % (scale * alignment) == 0 &&
        request.tileSize % (scale * alignment) == 0
    ) { "QNN UltraFix dimensions must align to the fixed latent origin grid." }

    fun axisPositions(extentPixels: Int): List<Int> {
        val extent = extentPixels / scale
        val tile = request.tileSize / scale
        require(extent > 0 && tile > 0 && tile <= extent)
        if (extent == tile) return listOf(0)
        val minimumOverlap = kotlin.math.floor(tile.toDouble() * request.overlap).toInt()
        require(minimumOverlap in 0 until tile)
        val maximumStride = tile - minimumOverlap
        val finalStart = extent - tile
        val intervalCount = (finalStart + maximumStride - 1) / maximumStride
        require(intervalCount > 0)
        val baseStride = finalStart / intervalCount
        val remainder = finalStart % intervalCount
        val unaligned = ArrayList<Int>(intervalCount + 1)
        unaligned += 0
        var cursor = 0
        repeat(intervalCount) { index ->
            val step = baseStride + if (index < remainder) 1 else 0
            require(step > 0) { "QNN UltraFix tile stride did not advance." }
            cursor += step
            unaligned += cursor
        }
        unaligned[unaligned.lastIndex] = finalStart
        val positions = unaligned.mapIndexed { index, position ->
            if (index == unaligned.lastIndex) finalStart else (position / alignment) * alignment
        }.distinct()
        require(positions.first() == 0 && positions.last() == finalStart) {
            "QNN UltraFix aligned tile grid did not cover both canvas edges."
        }
        return positions
    }

    val xPositions = axisPositions(request.targetWidth)
    val yPositions = axisPositions(request.targetHeight)
    val tile = request.tileSize / scale
    val descriptor = buildString {
        append("ultrafix-tile-plan-v2|target=")
        append(request.targetWidth).append('x').append(request.targetHeight)
        append("|tile=").append(request.tileSize)
        append("|scale=").append(scale)
        append("|alignment=").append(alignment)
        append("|overlapBits=").append(qnnUltraFixOverlapBits(request.overlap))
        append("|tiles=").append(xPositions.size.toLong() * yPositions.size.toLong())
        yPositions.forEachIndexed { yIndex, latentY ->
            xPositions.forEachIndexed { xIndex, latentX ->
                val left = if (xIndex == 0) 0 else
                    xPositions[xIndex - 1] + tile - latentX
                val right = if (xIndex + 1 == xPositions.size) 0 else
                    latentX + tile - xPositions[xIndex + 1]
                val top = if (yIndex == 0) 0 else
                    yPositions[yIndex - 1] + tile - latentY
                val bottom = if (yIndex + 1 == yPositions.size) 0 else
                    latentY + tile - yPositions[yIndex + 1]
                require(left in 0 until tile && right in 0 until tile &&
                    top in 0 until tile && bottom in 0 until tile
                ) { "QNN UltraFix tile overlap consumed an entire latent tile." }
                append('|')
                    .append(latentX * scale).append(',').append(latentY * scale).append(',')
                    .append(latentX).append(',').append(latentY).append(',')
                    .append(left * scale).append(',').append(right * scale).append(',')
                    .append(top * scale).append(',').append(bottom * scale).append(',')
                    .append(left).append(',').append(right).append(',')
                    .append(top).append(',').append(bottom)
            }
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(descriptor.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return LocalQnnUltraFixTilePlanEvidence(
        tileCount = xPositions.size.toLong() * yPositions.size.toLong(),
        tilePlanSha256 = digest
    )
}

private fun qnnUltraFixOverlapBits(value: Double): String =
    value.toRawBits().toULong().toString(radix = 16).padStart(16, '0')
