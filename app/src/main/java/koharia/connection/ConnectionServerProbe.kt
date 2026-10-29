package koharia.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// Flugel: anonymous product evidence is a prerequisite to sending credentials to an optional LAN address.
// A product fingerprint distinguishes unrelated devices; it is not cryptographic proof of an instance.
class ConnectionServerProbe internal constructor(val path: String, private val matches: (String) -> Boolean) {
    internal fun isAvailable(base: HttpUrl): Boolean {
        val url = base.resolve(path)?.takeIf { ConnectionAddressRouter.owns(base, it) } ?: return false
        val request = Request.Builder().url(url).header("Cache-Control", "no-store").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val source = response.body.source()
            source.request(MAX_BODY_BYTES + 1)
            if (source.buffer.size > MAX_BODY_BYTES) return@use false
            runCatching { matches(source.readUtf8()) }.getOrDefault(false)
        }
    }

    companion object {
        private const val MAX_BODY_BYTES = 64 * 1024L

        // Flugel: do not derive this client from a provider client: interceptors and authenticators can add secrets.
        private val client = OkHttpClient.Builder()
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cache(null)
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()

        val KOMGA = manifest("manifest.json", "Komga")
        val KAVITA = manifest("site.webmanifest", "Kavita")
        val LANRARAGI = ConnectionServerProbe("api/info") { body ->
            val info = Json.parseToJsonElement(body) as? JsonObject
            info != null && !info.text("version").isNullOrBlank() &&
                (info["server_tracks_progress"] as? JsonPrimitive)?.booleanOrNull != null &&
                (info["authenticated_progress"] as? JsonPrimitive)?.booleanOrNull != null &&
                ((info["archives_per_page"] as? JsonPrimitive)?.intOrNull ?: 0) > 0
        }
        val SUWAYOMI = ConnectionServerProbe("api/graphql?query=%7BaboutServer%7Bversion%7D%7D") { body ->
            val info = Json.parseToJsonElement(body) as? JsonObject
            val data = info?.get("data") as? JsonObject
            val server = data?.get("aboutServer") as? JsonObject
            return@ConnectionServerProbe server != null && !server.text("version").isNullOrBlank() &&
                info?.get("errors") == null
        }

        // Flugel: deploy/status only returns a generic code. Keep using the public endpoint until
        // Smanga offers anonymous product/instance evidence; a 200 or 401 alone is unsafe.
        val SMANGA = ConnectionServerProbe("deploy/status") { false }

        private fun manifest(path: String, name: String) = ConnectionServerProbe(path) { body ->
            val info = Json.parseToJsonElement(body) as? JsonObject
            info != null && info.text("name") == name && info.text("short_name") == name &&
                info.text("display") == "standalone"
        }

        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    }
}
