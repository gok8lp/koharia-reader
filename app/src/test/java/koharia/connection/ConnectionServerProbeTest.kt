package koharia.connection

import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

// Flugel: product responses, not reachability or an auth challenge, authorize optional LAN routing.
class ConnectionServerProbeTest {
    private var body = "{}"
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}/reader/".toHttpUrl()

    @AfterEach
    fun close() = server.stop(0)

    @Test
    fun `Kavita manifest proves product while generic health does not`() {
        body = "Ok"
        assertFalse(ConnectionServerProbe.KAVITA.isAvailable(base))
        body = """{"name":"Kavita","short_name":"Kavita","display":"standalone"}"""
        assertTrue(ConnectionServerProbe.KAVITA.isAvailable(base))
        assertFalse(ConnectionServerProbe.KOMGA.isAvailable(base))
    }

    @Test
    fun `LANraragi requires its documented info fields`() {
        body = """{"version":"1.0"}"""
        assertFalse(ConnectionServerProbe.LANRARAGI.isAvailable(base))
        body =
            """{"version":"0.9.70","server_tracks_progress":true,"authenticated_progress":true,"archives_per_page":100}"""
        assertTrue(ConnectionServerProbe.LANRARAGI.isAvailable(base))
    }

    @Test
    fun `Suwayomi requires a successful aboutServer GraphQL result`() {
        body = """{"data":{"aboutServer":{"version":"v2.4.2366"}}}"""
        assertTrue(ConnectionServerProbe.SUWAYOMI.isAvailable(base))
        body = """{"errors":[{"message":"Unauthorized"}]}"""
        assertFalse(ConnectionServerProbe.SUWAYOMI.isAvailable(base))
        body = """{"aboutServer":{"version":"v2.4.2366"}}"""
        assertFalse(ConnectionServerProbe.SUWAYOMI.isAvailable(base))
    }

    @Test
    fun `Smanga generic deployment status deliberately falls back to public`() {
        body = """{"code":200}"""
        assertFalse(ConnectionServerProbe.SMANGA.isAvailable(base))
    }
}
