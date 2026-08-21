package com.muyuchat.mca

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubReleaseUpdateTest {
    @Test
    fun releaseVersionParsingAndSemVerOrderingFollowExpectedPrecedence() {
        assertEquals(
            ReleaseVersion(1, 2, 3, listOf("rc", "2")),
            parseReleaseVersionOrNull("  v1.2.3-rc.2+build.9 ")
        )
        assertEquals("1.2.3-rc.2", parseReleaseVersionOrNull("V1.2.3-rc.2").toString())

        assertTrue(parseReleaseVersionOrNull("1.0.0-alpha")!! < parseReleaseVersionOrNull("1.0.0-alpha.1")!!)
        assertTrue(parseReleaseVersionOrNull("1.0.0-alpha.1")!! < parseReleaseVersionOrNull("1.0.0-alpha.beta")!!)
        assertTrue(parseReleaseVersionOrNull("1.0.0-alpha.beta")!! < parseReleaseVersionOrNull("1.0.0-beta")!!)
        assertTrue(parseReleaseVersionOrNull("1.0.0-beta")!! < parseReleaseVersionOrNull("1.0.0-beta.2")!!)
        assertTrue(parseReleaseVersionOrNull("1.0.0-beta.2")!! < parseReleaseVersionOrNull("1.0.0-beta.11")!!)
        assertTrue(parseReleaseVersionOrNull("1.0.0-beta.11")!! < parseReleaseVersionOrNull("1.0.0")!!)
        assertEquals(
            parseReleaseVersionOrNull("2.0.0"),
            parseReleaseVersionOrNull("v2.0.0+android")
        )
    }

    @Test
    fun invalidReleaseVersionsAndChecksumsAreRejected() {
        listOf(null, "", "1", "1.2", "1.2.3.4", "1.2.3-", "1.2.3-a..b", "release-1.2.3")
            .forEach { assertNull("Expected invalid version: $it", parseReleaseVersionOrNull(it)) }

        val checksum = "A".repeat(64)
        assertEquals(checksum.lowercase(), normalizeSha256OrNull(" sha256:$checksum "))
        assertEquals(checksum.lowercase(), normalizeSha256OrNull(checksum))
        assertNull(normalizeSha256OrNull("sha256:${"a".repeat(63)}"))
        assertNull(normalizeSha256OrNull("sha256:${"g".repeat(64)}"))
    }

    @Test
    fun archiveVersionMustMatchTheReleaseVersion() {
        val releaseVersion = parseReleaseVersionOrNull("v0.3.0")!!

        assertTrue(archiveVersionMatchesRelease("0.3.0", releaseVersion))
        assertTrue(archiveVersionMatchesRelease("v0.3.0+android.5", releaseVersion))
        assertFalse(archiveVersionMatchesRelease("0.3.1", releaseVersion))
        assertFalse(archiveVersionMatchesRelease("0.3.0-rc.1", releaseVersion))
        assertFalse(archiveVersionMatchesRelease("not-a-version", releaseVersion))
    }

    @Test
    fun releaseJsonParsesMetadataAndDropsMalformedAssets() {
        val digest = "B".repeat(64)
        val release = parseGitHubReleaseJson(
            """
            {
              "tag_name": "v0.3.0",
              "name": "MCA 0.3.0",
              "html_url": "https://github.com/lyydfys/MuYu-Chat-Agent/releases/tag/v0.3.0",
              "body": "Bug fixes",
              "published_at": "2026-08-21T00:00:00Z",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "mca-arm64-v8a.apk",
                  "browser_download_url": "https://github.com/lyydfys/MuYu-Chat-Agent/releases/download/v0.3.0/mca-arm64-v8a.apk",
                  "size": 1234,
                  "digest": "sha256:$digest"
                },
                {
                  "name": "mca-arm64-v8a.apk.sha256",
                  "browser_download_url": "https://objects.githubusercontent.com/assets/checksum",
                  "size": 72
                },
                { "name": "", "browser_download_url": "https://github.com/ignored" },
                { "name": "no-url.apk" }
              ]
            }
            """.trimIndent()
        )

        assertEquals("v0.3.0", release.tagName)
        assertEquals("MCA 0.3.0", release.name)
        assertEquals("Bug fixes", release.body)
        assertEquals(2, release.assets.size)
        assertEquals(1234L, release.assets[0].sizeBytes)
        assertEquals(digest.lowercase(), release.assets[0].digest)
        assertEquals(72L, release.assets[1].sizeBytes)
    }

    @Test
    fun releaseJsonRequiresTagName() {
        assertThrows(IllegalArgumentException::class.java) {
            parseGitHubReleaseJson("{\"name\":\"untagged\"}")
        }
    }

    @Test
    fun apkSelectionPrefersFirstSupportedAbiAndRejectsAmbiguity() {
        val arm = asset("mca-arm64-v8a.apk")
        val x86 = asset("mca-x86_64.apk")
        val universal = asset("mca-universal.apk")
        val unsafe = asset("mca arm64-v8a.apk")
        val untrusted = asset(
            name = "mca-riscv64.apk",
            url = "https://example.com/mca-riscv64.apk"
        )

        assertSame(arm, selectReleaseApkAsset(listOf(x86, arm, universal), listOf("arm64-v8a", "x86_64")))
        assertSame(x86, selectReleaseApkAsset(listOf(x86, arm), listOf("x86_64")))
        assertSame(universal, selectReleaseApkAsset(listOf(universal), listOf("unknown")))
        assertSame(universal, selectReleaseApkAsset(listOf(x86, universal), listOf("arm64-v8a")))
        assertNull(selectReleaseApkAsset(listOf(arm, asset("other-arm64-v8a.apk")), listOf("arm64-v8a")))
        assertNull(selectReleaseApkAsset(listOf(unsafe, untrusted), listOf("arm64-v8a", "riscv64")))
    }

    @Test
    fun checksumAssetMatchesApkStemBeforeUsingSingleFallback() {
        val apk = asset("mca-arm64-v8a.apk")
        val matching = asset("mca-arm64-v8a.apk.sha256")
        val unrelated = asset("checksums.sha256")

        assertSame(matching, selectReleaseChecksumAsset(listOf(unrelated, matching), apk))
        assertSame(unrelated, selectReleaseChecksumAsset(listOf(unrelated), apk))
        assertNull(selectReleaseChecksumAsset(emptyList(), apk))
        assertNull(selectReleaseChecksumAsset(listOf(unrelated, asset("other.sha256")), apk))
    }

    @Test
    fun updateCandidateOnlyContainsNewStableRelease() {
        val digest = "c".repeat(64)
        val apk = asset("mca-arm64-v8a.apk", digest = digest, size = 42)
        val checksum = asset("mca-arm64-v8a.apk.sha256")
        val release = release(tag = "v0.3.0", assets = listOf(apk, checksum))

        val candidate = buildAppUpdateCandidate(release, "0.2.1", listOf("arm64-v8a"))

        assertEquals("0.3.0", candidate?.version.toString())
        assertSame(apk, candidate?.apkAsset)
        assertSame(checksum, candidate?.checksumAsset)
        assertEquals(digest, candidate?.expectedSha256)
        assertTrue(candidate?.installable == true)

        assertNull(buildAppUpdateCandidate(release("v0.2.1"), "0.2.1", listOf("arm64-v8a")))
        assertNull(buildAppUpdateCandidate(release("v0.3.0", draft = true), "0.2.1", listOf("arm64-v8a")))
        assertNull(buildAppUpdateCandidate(release("v0.3.0", prerelease = true), "0.2.1", listOf("arm64-v8a")))
        assertNull(buildAppUpdateCandidate(release("v0.3.0-rc.1"), "0.2.1", listOf("arm64-v8a")))
        assertNull(buildAppUpdateCandidate(release("not-semver"), "0.2.1", listOf("arm64-v8a")))
        assertNull(buildAppUpdateCandidate(release, "local-build", listOf("arm64-v8a")))
    }

    @Test
    fun candidateMayBeMetadataOnlyWhenNoCompatibleApkExists() {
        val release = release(
            tag = "v0.3.0",
            assets = listOf(asset("mca-x86_64.apk"), asset("mca-riscv64.apk"))
        )
        val candidate = buildAppUpdateCandidate(release, "0.2.1", listOf("arm64-v8a"))

        assertTrue(candidate != null)
        assertNull(candidate?.apkAsset)
        assertFalse(candidate?.installable == true)
    }

    @Test
    fun sha256SidecarAcceptsCommonFormatsAndRequiresOneUnambiguousEntry() {
        val checksum = "d".repeat(64)
        assertEquals(
            checksum,
            parseSha256Sidecar(
                "# generated by CI\n$checksum  *mca-arm64-v8a.apk\n",
                "mca-arm64-v8a.apk"
            )
        )
        assertEquals(
            checksum,
            parseSha256Sidecar("$checksum  /tmp/build/mca-arm64-v8a.apk\n", "mca-arm64-v8a.apk")
        )
        assertEquals(checksum, parseSha256Sidecar("$checksum\n", "mca-arm64-v8a.apk"))
        assertNull(
            parseSha256Sidecar(
                "$checksum  first.apk\n$checksum  second.apk\n",
                "mca-arm64-v8a.apk"
            )
        )
        assertNull(parseSha256Sidecar("${"e".repeat(63)}  mca-arm64-v8a.apk", "mca-arm64-v8a.apk"))
        assertNull(parseSha256Sidecar("$checksum  other.apk", "mca-arm64-v8a.apk"))
    }

    @Test
    fun githubUrlsAreRestrictedToHttpsGithubHosts() {
        assertTrue(isTrustedGitHubDownloadUrl("https://github.com/owner/repo/releases/download/v1/app.apk"))
        assertTrue(isTrustedGitHubDownloadUrl("https://objects.githubusercontent.com/a"))
        assertTrue(isTrustedGitHubDownloadUrl("https://release-assets.githubusercontent.com/a"))
        assertTrue(isTrustedGitHubDownloadUrl("https://github-releases.githubusercontent.com/a"))
        assertFalse(isTrustedGitHubDownloadUrl("https://foo.githubusercontent.com/a"))
        assertFalse(isTrustedGitHubDownloadUrl("http://github.com/owner/repo/app.apk"))
        assertFalse(isTrustedGitHubDownloadUrl("https://github.com.evil.example/app.apk"))
        assertFalse(isTrustedGitHubDownloadUrl("https://githubusercontent.com.evil.example/app.apk"))
        assertFalse(isTrustedGitHubDownloadUrl("not a url"))
    }

    @Test
    fun githubClientSendsEtagAndParsesFetchedRelease() {
        val body = """
            {"tag_name":"v0.3.0","name":"MCA 0.3.0","assets":[]}
        """.trimIndent()
        var seenIfNoneMatch: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                seenIfNoneMatch = chain.request().header("If-None-Match")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("ETag", "\"release-1\"")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val result = GitHubReleaseClient(client).fetchLatest("\"old\"")

        assertEquals("\"old\"", seenIfNoneMatch)
        assertTrue(result is GitHubReleaseFetchResult.Fetched)
        result as GitHubReleaseFetchResult.Fetched
        assertEquals("v0.3.0", result.release.tagName)
        assertEquals(body, result.rawJson)
        assertEquals("\"release-1\"", result.etag)
    }

    @Test
    fun githubClientPreservesEtagOnNotModifiedAndSurfacesHttpErrors() {
        val notModifiedClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(304)
                    .message("Not Modified")
                    .body("".toResponseBody())
                    .build()
            })
            .build()
        val notModified = GitHubReleaseClient(notModifiedClient).fetchLatest("\"cached\"")
        assertEquals(
            GitHubReleaseFetchResult.NotModified("\"cached\""),
            notModified
        )

        val errorClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Unavailable")
                    .body("busy".toResponseBody("text/plain".toMediaType()))
                    .build()
            })
            .build()
        val error = assertThrows(IllegalArgumentException::class.java) {
            GitHubReleaseClient(errorClient).fetchLatest()
        }
        assertTrue(error.message.orEmpty().contains("HTTP 503"))
    }

    @Test
    fun githubClientFetchTextRejectsUntrustedUrlBeforeNetwork() {
        val client = GitHubReleaseClient(OkHttpClient())
        val error = assertThrows(IllegalArgumentException::class.java) {
            client.fetchText("https://example.com/checksum.sha256")
        }
        assertTrue(error.message.orEmpty().contains("受信任"))
    }

    @Test
    fun githubClientDoesNotPermitAnAlternateReleaseEndpoint() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            GitHubReleaseClient(OkHttpClient(), "https://example.com/releases/latest").fetchLatest()
        }

        assertTrue(error.message.orEmpty().contains("official repository"))
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<GitHubReleaseAsset> = emptyList()
    ) = GitHubRelease(
        tagName = tag,
        name = tag,
        htmlUrl = GITHUB_RELEASE_PAGE_URL,
        body = "notes",
        publishedAt = "",
        draft = draft,
        prerelease = prerelease,
        assets = assets
    )

    private fun asset(
        name: String,
        url: String = "https://github.com/lyydfys/MuYu-Chat-Agent/releases/download/v0.3.0/$name",
        digest: String? = null,
        size: Long = 1L
    ) = GitHubReleaseAsset(
        name = name,
        downloadUrl = url,
        sizeBytes = size,
        digest = digest
    )
}
