package eu.kanade.tachiyomi.data.updater

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest

// Flugel: personal releases require HTTPS metadata and a SHA-256 before any installation.
@Serializable
internal data class FlugelUpdateManifest(
    val versionCode: Int,
    val url: String,
    val sha256: String,
    val versionName: String? = null,
    val size: Long? = null,
) {
    fun validate(): FlugelUpdateManifest = apply {
        require(versionCode > 0) { "Invalid update version code" }
        requireSecureUrl(url)
        require(SHA256.matches(sha256)) { "Missing or invalid update SHA-256" }
        require(size == null || size > 0) { "Invalid update size" }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val SHA256 = Regex("[0-9a-fA-F]{64}")

        fun parse(body: String): FlugelUpdateManifest = json.decodeFromString<FlugelUpdateManifest>(body).validate()

        fun requireSecureUrl(value: String) {
            val url = requireNotNull(value.toHttpUrlOrNull()) { "Invalid update URL" }
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
                "Updates require HTTPS URLs without credentials or fragments"
            }
        }

        fun requireDownloadHash(value: String?, mandatory: Boolean): String? {
            require(!mandatory || (value != null && SHA256.matches(value))) { "Update SHA-256 is required" }
            return value?.takeIf(SHA256::matches)
        }

        fun verifyDownload(file: File, expectedSha256: String) {
            require(SHA256.matches(expectedSha256)) { "Invalid update SHA-256" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actual.equals(expectedSha256, ignoreCase = true)) { "Update SHA-256 mismatch" }
        }
    }
}
