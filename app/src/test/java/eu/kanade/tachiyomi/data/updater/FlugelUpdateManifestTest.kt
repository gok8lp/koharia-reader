package eu.kanade.tachiyomi.data.updater

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

// Flugel: metadata and the downloaded bytes must both pass validation.
class FlugelUpdateManifestTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `accepts HTTPS release with mandatory checksum and forward compatible metadata`() {
        val manifest = FlugelUpdateManifest.parse(
            """{"versionCode":100001,"url":"https://example.com/flugel.apk","sha256":"$HASH","future":true}""",
        )
        assertEquals(100001, manifest.versionCode)
        assertEquals(HASH, manifest.sha256)
    }

    @Test
    fun `rejects missing or malformed checksum`() {
        listOf("", "abc", "g".repeat(64), "a".repeat(63), "a".repeat(65)).forEach { hash ->
            assertThrows(IllegalArgumentException::class.java) {
                FlugelUpdateManifest(100001, "https://example.com/app.apk", hash).validate()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlugelUpdateManifest.parse("""{"versionCode":100001,"url":"https://example.com/app.apk"}""")
        }
    }

    @Test
    fun `rejects insecure or credential carrying URLs and invalid version or size`() {
        listOf(
            "http://example.com/app.apk",
            "https://user:secret@example.com/app.apk",
            "file:///app.apk",
            "https://example.com/app.apk#fragment",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                FlugelUpdateManifest(100001, url, HASH).validate()
            }
        }
        listOf(0, -1).forEach { code ->
            assertThrows(IllegalArgumentException::class.java) {
                FlugelUpdateManifest(code, "https://example.com/app.apk", HASH).validate()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlugelUpdateManifest(100001, "https://example.com/app.apk", HASH, size = 0).validate()
        }
    }

    @Test
    fun `personal download cannot omit or discard a malformed checksum`() {
        listOf(null, "", "bad").forEach { hash ->
            assertThrows(IllegalArgumentException::class.java) {
                FlugelUpdateManifest.requireDownloadHash(hash, mandatory = true)
            }
        }
        assertEquals(HASH, FlugelUpdateManifest.requireDownloadHash(HASH, mandatory = true))
    }

    @Test
    fun `accepts matching bytes and rejects tampered or truncated download`() {
        val apk = directory.resolve("update.apk").toFile()
        apk.writeText("abc")
        FlugelUpdateManifest.verifyDownload(apk, HASH.uppercase())
        listOf("abd", "ab", "").forEach { changed ->
            apk.writeText(changed)
            assertThrows(IllegalArgumentException::class.java) { FlugelUpdateManifest.verifyDownload(apk, HASH) }
        }
    }

    companion object {
        private const val HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
