package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Test

/** R1/S3: SHA-256 幂等键哈希的已知向量保护网（纯 JVM 执行）。 */
class LocalApiIdempotencySupportTest {

    @Test
    fun `hash matches published NIST vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            localApiIdempotencyKeyHash("")
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            localApiIdempotencyKeyHash("abc")
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            localApiIdempotencyKeyHash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
        )
    }

    @Test
    fun `hash is stable across calls and encodes bytes lowercase hex`() {
        val first = localApiIdempotencyKeyHash("chat:sess-1:turn-7")
        val second = localApiIdempotencyKeyHash("chat:sess-1:turn-7")
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertEquals(first.lowercase(), first)
    }
}
