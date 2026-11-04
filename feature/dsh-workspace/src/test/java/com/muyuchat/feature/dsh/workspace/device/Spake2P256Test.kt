package com.muyuchat.feature.dsh.workspace.device

import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPAKE2 over P-256 (RFC 9382): published test vectors, two-party agreement and the abort paths.
 *
 * The four Appendix B vectors are the strongest check in this module: they pin the masking points
 * M/N, the transcript byte layout (eight-byte little-endian lengths), SHA-256, HKDF-SHA256 and the
 * HMAC confirmation keys at once. `pA`, `pB` and `K` also exercise scalar multiplication of injected
 * scalars, so an EC error cannot hide behind a self-consistent transcript.
 */
class Spake2P256Test {

    @Test
    fun `rfc 9382 vector 1 - named identities`() {
        assertVector(
            identityA = "server",
            identityB = "client",
            w = "2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f",
            scalarA = "43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729",
            expectedShareA = "04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470" +
                "f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c",
            scalarB = "dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be",
            expectedShareB = "0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676" +
                "d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7",
            expectedSharedPoint = "0412af7e89717850671913e6b469ace67bd90a4df8ce45c2af19010175e37eed" +
                "69f75897996d539356e2fa6a406d528501f907e04d97515fbe83db277b715d3325",
            expectedTranscriptHash = "0e0672dc86f8e45565d338b0540abe6915bdf72e2b35b5c9e5663168e960a91b",
            expectedConfirmKeyA = "00c12546835755c86d8c0db7851ae86f",
            expectedConfirmKeyB = "a9fa3406c3b781b93d804485430ca27a",
            expectedConfirmA = "58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0",
            expectedConfirmB = "d3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b"
        )
    }

    @Test
    fun `rfc 9382 vector 2 - empty A identity`() {
        assertVector(
            identityA = "",
            identityB = "client",
            w = "0548d8729f730589e579b0475a582c1608138ddf7054b73b5381c7e883e2efae",
            scalarA = "403abbe3b1b4b9ba17e3032849759d723939a27a27b9d921c500edde18ed654b",
            expectedShareA = "04a897b769e681c62ac1c2357319a3d363f610839c4477720d24cbe32f5fd8" +
                "5f44fb92ba966578c1b712be6962498834078262caa5b441ecfa9d4a9485720e918a",
            scalarB = "903023b6598908936ea7c929bd761af6039577a9c3f9581064187c3049d87065",
            expectedShareB = "04e0f816fd1c35e22065d5556215c097e799390d16661c386e0ecc84593974" +
                "a61b881a8c82327687d0501862970c64565560cb5671f696048050ca66ca5f8cc7fc",
            expectedSharedPoint = "048f83ec9f6e4f87cc6f9dc740bdc2769725f923364f01c84148c049a39a735e" +
                "bda82eac03e00112fd6a5710682767cff5361f7e819e53d8d3c3a2922e0d837aa6",
            expectedTranscriptHash = "642f05c473c2cd79909f9a841e2f30a70bf89b18180af97353ba198789c2b963",
            expectedConfirmKeyA = "c6be376fc7cd1301fd0a13adf3e7bffd",
            expectedConfirmKeyB = "b7243f4ae60440a49b3f8cab3c1fba07",
            expectedConfirmA = "47d29e6666af1b7dd450d571233085d7a9866e4d49d2645e2df975489521232b",
            expectedConfirmB = "3313c5cefc361d27fb16847a91c2a73b766ffa90a4839122a9b70a2f6bd1d6df"
        )
    }

    @Test
    fun `rfc 9382 vector 3 - empty B identity`() {
        assertVector(
            identityA = "server",
            identityB = "",
            w = "626e0cdc7b14c9db3e52a0b1b3a768c98e37852d5db30febe0497b14eae8c254",
            scalarA = "07adb3db6bc623d3399726bfdbfd3d15a58ea776ab8a308b00392621291f9633",
            expectedShareA = "04f88fb71c99bfffaea370966b7eb99cd4be0ff1a7d335caac4211c4afd855e2" +
                "e15a873b298503ad8ba1d9cbb9a392d2ba309b48bfd7879aefd0f2cea6009763b0",
            scalarB = "b6a4fc8dbb629d4ba51d6f91ed1532cf87adec98f25dd153a75accafafedec16",
            expectedShareB = "040c269d6be017dccb15182ac6bfcd9e2a14de019dd587eaf4bdfd353f031101" +
                "e7cca177f8eb362a6e83e7d5e729c0732e1b528879c086f39ba0f31a9661bd34db",
            expectedSharedPoint = "0445ee233b8ecb51ebd6e7da3f307e88a1616bae2166121221fdc0dadb986afa" +
                "f3ec8a988dc9c626fa3b99f58a7ca7c9b844bb3e8dd9554aafc5b53813504c1cbe",
            expectedTranscriptHash = "005184ff460da2ce59062c87733c299c3521297d736598fc0a1127600efa1afb",
            expectedConfirmKeyA = "f3da53604f0aeecea5a33be7bddf6edf",
            expectedConfirmKeyB = "9e3f86848736f159bd92b6e107ec6799",
            expectedConfirmA = "bc9f9bbe99f26d0b2260e6456e05a86196a3307ec6663a18bf6ac825736533b2",
            expectedConfirmB = "c2370e1bf813b086dff0d834e74425a06e6390f48f5411900276dcccc5a297ec"
        )
    }

    @Test
    fun `rfc 9382 vector 4 - both identities empty`() {
        assertVector(
            identityA = "",
            identityB = "",
            w = "7bf46c454b4c1b25799527d896508afd5fc62ef4ec59db1efb49113063d70cca",
            scalarA = "8cef65df64bb2d0f83540c53632de911b5b24b3eab6cc74a97609fd659e95473",
            expectedShareA = "04a65b367a3f613cf9f0654b1b28a1e3a8a40387956c8ba6063e8658563890f4" +
                "6ca1ef6a676598889fc28de2950ab8120b79a5ef1ea4c9f44bc98f585634b46d66",
            scalarB = "d7a66f64074a84652d8d623a92e20c9675c61cb5b4f6a0063e4648a2fdc02d53",
            expectedShareB = "04589f13218822710d98d8b2123a079041052d9941b9cf88c6617ddb2fcc0494" +
                "662eea8ba6b64692dc318250030c6af045cb738bc81ba35b043c3dcb46adf6f58d",
            expectedSharedPoint = "041a3c03d51b452537ca2a1fea6110353c6d5ed483c4f0f86f4492ca3f378d40" +
                "a994b4477f93c64d928edbbcd3e85a7c709b7ea73ee97986ce3d1438e135543772",
            expectedTranscriptHash = "fc6374762ba5cf11f4b2caa08b2cd1b9907ae0e26e8d6234318d91583cd74c86",
            expectedConfirmKeyA = "5dbd2f477166b7fb6d61febbd77a5563",
            expectedConfirmKeyB = "7689b4654407a5faeffdc8f18359d8a3",
            expectedConfirmA = "dfb4db8d48ae5a675963ea5e6c19d98d4ea028d8e898dad96ea19a80ade95dca",
            expectedConfirmB = "d0f0609d1613138d354f7e95f19fb556bf52d751947241e8c7118df5ef0ae175"
        )
    }

    @Test
    fun `two parties with the same password agree and confirm each other`() {
        val policy = fastPolicy()
        val password = "123456".toByteArray(Charsets.US_ASCII)
        val client = Spake2P256.start(Spake2Role.A, CLIENT_ID, SERVER_ID, password, policy)
        val server = Spake2P256.start(Spake2Role.B, CLIENT_ID, SERVER_ID, password, policy)

        assertEquals(65, client.share.size)
        assertEquals(0x04, client.share[0].toInt())
        val clientResult = requireNotNull(client.complete(server.share))
        val serverResult = requireNotNull(server.complete(client.share))

        assertArrayEquals(clientResult.sharedKey, serverResult.sharedKey)
        assertEquals(clientResult.sharedKey.size, clientResult.transcriptHash.size / 2)
        assertTrue(
            "client must accept the server's confirmation",
            clientResult.verifyPeerConfirm(serverResult.ownConfirm)
        )
        assertTrue(
            "server must accept the client's confirmation",
            serverResult.verifyPeerConfirm(clientResult.ownConfirm)
        )
        // Each side MACs with its own key, so the two messages must differ.
        assertNotEquals(hex(clientResult.ownConfirm), hex(serverResult.ownConfirm))
        // Both sides hashed the same transcript (same identities, shares and K).
        assertEquals(hex(clientResult.transcript), hex(serverResult.transcript))
        assertFalse(clientResult.verifyPeerConfirm(clientResult.ownConfirm))
    }

    @Test
    fun `different passwords produce different keys and fail confirmation`() {
        val policy = fastPolicy()
        val client = Spake2P256.start(Spake2Role.A, CLIENT_ID, SERVER_ID, "123456".toByteArray(), policy)
        val server = Spake2P256.start(Spake2Role.B, CLIENT_ID, SERVER_ID, "123457".toByteArray(), policy)

        val clientResult = requireNotNull(client.complete(server.share))
        val serverResult = requireNotNull(server.complete(client.share))

        assertNotEquals(hex(clientResult.sharedKey), hex(serverResult.sharedKey))
        assertFalse(clientResult.verifyPeerConfirm(serverResult.ownConfirm))
        assertFalse(serverResult.verifyPeerConfirm(clientResult.ownConfirm))
    }

    @Test
    fun `empty malformed and identity peer shares abort`() {
        val policy = fastPolicy()
        val password = "123456".toByteArray()
        val client = Spake2P256.start(Spake2Role.A, CLIENT_ID, SERVER_ID, password, policy)

        assertNull("empty share", client.complete(ByteArray(0)))
        assertNull("truncated share", client.complete(ByteArray(64) { 0x04 }))
        assertNull("SEC1 infinity", client.complete(byteArrayOf(0x00)))
        assertNull("uncompressed point off the curve", client.complete(offCurvePoint()))
        // A share that unmasks to the identity must be refused rather than producing K = infinity.
        val w = policy.deriveW(password)
        val identityShare = P256.multiply(w, Spake2Suite.RFC9382_P256_SHA256.maskForB)
            .encodeUncompressed()
        assertNull("share that unmasks to the identity", client.complete(identityShare))
    }

    @Test
    fun `a long password is accepted and still agrees`() {
        val policy = Spake2PasswordPolicy(salt = "long".toByteArray(), iterations = 50)
        val password = "9".repeat(4_096).toByteArray(Charsets.US_ASCII)
        val client = Spake2P256.start(Spake2Role.A, CLIENT_ID, SERVER_ID, password, policy)
        val server = Spake2P256.start(Spake2Role.B, CLIENT_ID, SERVER_ID, password, policy)

        val clientResult = requireNotNull(client.complete(server.share))
        val serverResult = requireNotNull(server.complete(client.share))

        assertArrayEquals(clientResult.sharedKey, serverResult.sharedKey)
        assertTrue(clientResult.verifyPeerConfirm(serverResult.ownConfirm))
    }

    @Test
    fun `password policy is deterministic and salt sensitive`() {
        val password = "123456".toByteArray()
        val first = Spake2PasswordPolicy(salt = "salt-a".toByteArray(), iterations = 10)
        val second = Spake2PasswordPolicy(salt = "salt-b".toByteArray(), iterations = 10)
        val repeat = Spake2PasswordPolicy(salt = "salt-a".toByteArray(), iterations = 10)

        assertEquals(first.deriveW(password), repeat.deriveW(password))
        assertNotEquals(first.deriveW(password), second.deriveW(password))
        assertNotEquals(first.deriveW(password), first.deriveW("654321".toByteArray()))
        for (policy in listOf(first, second)) {
            val w = policy.deriveW(password)
            assertTrue("w must be in (0, n)", w.signum() > 0 && w < P256.n)
        }
        assertTrue(
            "PBKDF2 must produce a full-width field element",
            first.deriveW(password).bitLength() > 128
        )
    }

    @Test
    fun `masking points are injectable so a different suite can be plugged in`() {
        val customSuite = Spake2Suite.of(
            compressedMaskA = P256.multiply(BigInteger.valueOf(11), P256.g).encodeCompressed(),
            compressedMaskB = P256.multiply(BigInteger.valueOf(13), P256.g).encodeCompressed()
        )
        val policy = fastPolicy()
        val password = "abcdef".toByteArray()
        val client = Spake2P256.start(Spake2Role.A, CLIENT_ID, SERVER_ID, password, policy, suite = customSuite)
        val server = Spake2P256.start(Spake2Role.B, CLIENT_ID, SERVER_ID, password, policy, suite = customSuite)

        val clientResult = requireNotNull(client.complete(server.share))
        val serverResult = requireNotNull(server.complete(client.share))

        assertArrayEquals(clientResult.sharedKey, serverResult.sharedKey)
        assertTrue(clientResult.verifyPeerConfirm(serverResult.ownConfirm))
    }

    @Test
    fun `transcript length prefix is eight bytes little endian`() {
        // Regression: `Int ushr 32` is masked to `ushr 0` by the JVM and repeated the low byte.
        assertArrayEquals(byteArrayOf(6, 0, 0, 0, 0, 0, 0, 0), littleEndianLength(6))
        assertArrayEquals(byteArrayOf(0x41, 0, 0, 0, 0, 0, 0, 0), littleEndianLength(65))
        assertArrayEquals(
            byteArrayOf(0x00, 0x01, 0, 0, 0, 0, 0, 0),
            littleEndianLength(256)
        )
    }

    @Test
    fun `constant time comparison only matches equal byte strings`() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun `password is required and scalars are validated`() {
        assertTrue(
            runCatching {
                Spake2P256.start(Spake2Role.A, CLIENT_ID, SERVER_ID, ByteArray(0))
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching {
                Spake2P256.startWithScalars(
                    Spake2Role.A, CLIENT_ID, SERVER_ID, BigInteger.ZERO, BigInteger.ONE
                )
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching {
                Spake2PasswordPolicy(salt = ByteArray(0)).deriveW(byteArrayOf(1))
            }.exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun `suite rejects masking points that are not on the curve`() {
        val offCurve = ByteArray(65)
        offCurve[0] = 0x04
        P256.toFixed(BigInteger.ONE).copyInto(offCurve, 1)
        P256.toFixed(BigInteger.valueOf(5)).copyInto(offCurve, 33)
        assertTrue(
            runCatching {
                Spake2Suite.of(compressedMaskA = offCurve, compressedMaskB = offCurve)
            }.exceptionOrNull() is IllegalArgumentException
        )
        assertNotNull(Spake2Suite.RFC9382_P256_SHA256.maskForA)
        assertNotNull(Spake2Suite.RFC9382_P256_SHA256.maskForB)
    }

    private fun assertVector(
        identityA: String,
        identityB: String,
        w: String,
        scalarA: String,
        expectedShareA: String,
        scalarB: String,
        expectedShareB: String,
        expectedSharedPoint: String,
        expectedTranscriptHash: String,
        expectedConfirmKeyA: String,
        expectedConfirmKeyB: String,
        expectedConfirmA: String,
        expectedConfirmB: String
    ) {
        val roleA = Spake2P256.startWithScalars(
            role = Spake2Role.A,
            identityA = identityA.toByteArray(Charsets.US_ASCII),
            identityB = identityB.toByteArray(Charsets.US_ASCII),
            scalar = BigInteger(scalarA, 16),
            passwordScalar = BigInteger(w, 16)
        )
        val roleB = Spake2P256.startWithScalars(
            role = Spake2Role.B,
            identityA = identityA.toByteArray(Charsets.US_ASCII),
            identityB = identityB.toByteArray(Charsets.US_ASCII),
            scalar = BigInteger(scalarB, 16),
            passwordScalar = BigInteger(w, 16)
        )

        assertEquals("pA", expectedShareA, hex(roleA.share))
        assertEquals("pB", expectedShareB, hex(roleB.share))

        val resultA = requireNotNull(roleA.complete(roleB.share)) { "role A rejected the RFC share" }
        val resultB = requireNotNull(roleB.complete(roleA.share)) { "role B rejected the RFC share" }

        assertEquals("K", expectedSharedPoint, hex(resultA.sharedPoint))
        assertEquals("K (role B)", hex(resultA.sharedPoint), hex(resultB.sharedPoint))
        assertEquals("Hash(TT)", expectedTranscriptHash, hex(resultA.transcriptHash))
        assertEquals("Ke = first half of Hash(TT)", expectedTranscriptHash.substring(0, 32), hex(resultA.sharedKey))
        assertEquals(
            "Ka = second half of Hash(TT)",
            expectedTranscriptHash.substring(32),
            hex(resultA.confirmationMaterial)
        )
        assertEquals("KcA", expectedConfirmKeyA, hex(resultA.confirmKeyA))
        assertEquals("KcB", expectedConfirmKeyB, hex(resultA.confirmKeyB))
        assertEquals("cA", expectedConfirmA, hex(resultA.ownConfirm))
        assertEquals("cB", expectedConfirmB, hex(resultA.expectedPeerConfirm))
        assertArrayEquals("both roles derive the same Ke", resultA.sharedKey, resultB.sharedKey)
        assertTrue("A verifies cB", resultA.verifyPeerConfirm(resultB.ownConfirm))
        assertTrue("B verifies cA", resultB.verifyPeerConfirm(resultA.ownConfirm))
    }

    private fun fastPolicy() = Spake2PasswordPolicy(salt = "mca-test-salt".toByteArray(), iterations = 200)

    /** A syntactically valid 65-byte encoding whose point is not on the curve. */
    private fun offCurvePoint(): ByteArray {
        val encoded = ByteArray(65)
        encoded[0] = 0x04
        P256.toFixed(BigInteger.ONE).copyInto(encoded, 1)
        P256.toFixed(BigInteger.valueOf(2)).copyInto(encoded, 33)
        return encoded
    }

    private companion object {
        val CLIENT_ID = "adb pair client".toByteArray(Charsets.US_ASCII)
        val SERVER_ID = "adb pair server".toByteArray(Charsets.US_ASCII)

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
