package koharia.connection

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class ConnectionAddressRouterTest {
    private val publicRequests = CopyOnWriteArrayList<String>()
    private val internalRequests = CopyOnWriteArrayList<String>()
    private var internalStatus = 200
    private var probeStatus = 200
    private var probeBody = """{"name":"Komga","short_name":"Komga","display":"standalone"}"""
    private var probeDelay = 0L
    private val internalSecrets = CopyOnWriteArrayList<List<String?>>()
    private var wifi: Any? = "home"
    private val publicServer = server(publicRequests) { 200 }
    private val internalServer =
        server(internalRequests) { if (it.endsWith("/manifest.json")) probeStatus else internalStatus }
    private val public = "http://127.0.0.1:${publicServer.address.port}/public/"
    private var internal = "http://127.0.0.1:${internalServer.address.port}/lan/"
    private val router = ConnectionAddressRouter({ public }, { internal }, { wifi }, ConnectionServerProbe.KOMGA)
    private val client = OkHttpClient.Builder().addInterceptor(router)
        .addNetworkInterceptor(ConnectionAddressRouter.redirectGuard).build()

    @AfterEach
    fun close() {
        publicServer.stop(0)
        internalServer.stop(0)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `wifi routes reads and range headers without changing canonical response URL`() {
        val request = Request.Builder().url(public + "book/page?number=2").header("Range", "bytes=3-9").build()
        client.newCall(request).execute().use {
            assertEquals(request.url, it.request.url)
            assertEquals("bytes=3-9", it.header("Received-Range"))
        }
        assertEquals(listOf("GET /lan/manifest.json", "GET /lan/book/page?number=2"), internalRequests)
        assertTrue(publicRequests.isEmpty())
    }

    @Test
    fun `cached only requests never probe or access network`() {
        client.newCall(Request.Builder().url(public + "book").header("Cache-Control", "only-if-cached").build())
            .execute().use { assertEquals(504, it.code) }
        assertTrue(publicRequests.isEmpty())
        assertTrue(internalRequests.isEmpty())
    }

    @Test
    fun `cellular and blank optional address use only public`() {
        wifi = null
        get()
        wifi = "home"
        internal = ""
        get()
        assertEquals(2, publicRequests.size)
        assertTrue(internalRequests.isEmpty())
    }

    @Test
    fun `unreachable internal falls back and avoids repeated probes until network changes`() {
        probeStatus = 503
        get()
        get()
        assertEquals(1, internalRequests.size)
        assertEquals(2, publicRequests.size)
        wifi = "other wifi"
        probeStatus = 200
        get()
        assertEquals(3, internalRequests.size)
        assertEquals(2, publicRequests.size)
    }

    @Test
    fun `lost internal connection falls back to public`() {
        get()
        internalServer.stop(0)
        get()
        assertEquals(1, publicRequests.size)
    }

    @Test
    fun `write is not replayed after server failure`() {
        internalStatus = 503
        client.newCall(Request.Builder().url(public + "progress").put("{}".toRequestBody()).build())
            .execute().use { assertEquals(503, it.code) }
        assertTrue(publicRequests.isEmpty())
        assertEquals(listOf("GET /lan/manifest.json", "PUT /lan/progress"), internalRequests)
    }

    @Test
    fun `failed probe selects public before writing`() {
        probeStatus = 503
        client.newCall(Request.Builder().url(public + "progress").put("{}".toRequestBody()).build())
            .execute().close()
        assertEquals(listOf("GET /lan/manifest.json"), internalRequests)
        assertEquals(listOf("PUT /public/progress"), publicRequests)
    }

    @Test
    fun `read transport failure falls back but authentication response does not`() {
        internalStatus = 401
        get()
        assertTrue(publicRequests.isEmpty())
        internalStatus = 503
        get()
        assertEquals(1, publicRequests.size)
    }

    @Test
    fun `authentication failures cannot validate an unknown internal server`() {
        for (status in listOf(401, 403)) {
            probeStatus = status
            internalStatus = status
            wifi = status
            get()
        }
        assertEquals(2, publicRequests.size)
        assertEquals(2, internalRequests.size)
    }

    @Test
    fun `anonymous probe cannot inherit provider interceptors cookies headers or secret URL parameters`() {
        val credentialedClient = client.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "interceptor-secret").build())
        }.build()
        try {
            credentialedClient.newCall(
                Request.Builder().url(public + "book?apiKey=fixture-secret")
                    .header("Authorization", "request-secret")
                    .header("X-API-Key", "key-secret")
                    .header("X-Komga-Api-Key", "legacy-key-secret")
                    .header("Cookie", "session=secret")
                    .header("token", "token-secret").build(),
            ).execute().close()
            assertEquals("GET /lan/manifest.json", internalRequests.first())
            assertTrue(internalSecrets.first().all { it == null })
            assertTrue(internalSecrets.last().any { it != null })
            assertTrue(publicRequests.isEmpty())
        } finally {
            credentialedClient.connectionPool.evictAll()
        }
    }

    @Test
    fun `wrong product malformed JSON generic health and oversized response cannot validate LAN`() {
        for (body in listOf(
            "Ok",
            "{}",
            "{",
            """{"name":"Kavita","short_name":"Kavita","display":"standalone"}""",
            " ".repeat(65537),
        )) {
            wifi = body
            probeBody = body
            get()
        }
        assertEquals(5, publicRequests.size)
        assertEquals(5, internalRequests.size)
    }

    @Test
    fun `anonymous probe redirects are not followed`() {
        probeStatus = 302
        get()
        assertEquals(listOf("GET /lan/manifest.json"), internalRequests)
        assertEquals(listOf("GET /public/book"), publicRequests)
    }

    @Test
    fun `slow anonymous probe falls back once without credentials`() {
        probeDelay = 2000
        get()
        get()
        assertEquals(2, publicRequests.size)
        assertEquals(1, internalRequests.size)
        assertTrue(internalSecrets.single().all { it == null })
    }

    @Test
    fun `overlapping base paths use the more specific prefix`() {
        val nestedInternal = ConnectionAddressRouter(
            { "https://public.test/lrr/" },
            { "http://lan.test/lrr/internal/" },
            { "wifi" },
            ConnectionServerProbe.KOMGA,
        )
        assertEquals(
            "https://public.test/lrr/api/image/1",
            nestedInternal.canonicalResourcePath("/lrr/internal/api/image/1"),
        )
        val nestedPublic = ConnectionAddressRouter(
            { "https://public.test/lrr/public/" },
            { "http://lan.test/lrr/" },
            { "wifi" },
            ConnectionServerProbe.KOMGA,
        )
        assertEquals("/lrr/public/api/image/1", nestedPublic.canonicalResourcePath("/lrr/public/api/image/1"))
        assertEquals(
            "https://public.test/lrr/public/api/image/1",
            nestedPublic.canonicalResourcePath("/lrr/api/image/1"),
        )
    }

    @Test
    fun `base path boundaries and embedded internal URLs remain canonical`() {
        val base = checkNotNull(ConnectionAddressRouter.normalize(public))
        assertFalse(
            ConnectionAddressRouter.owns(
                base,
                checkNotNull(ConnectionAddressRouter.normalize(public + "../public-other")),
            ),
        )
        val local = checkNotNull(ConnectionAddressRouter.normalize(internal + "image"))
        assertEquals(public + "image/", router.canonicalUrl(local).toString())
        assertEquals(null, ConnectionAddressRouter.normalize("https://user:password@host"))
    }

    private fun get() = client.newCall(Request.Builder().url(public + "book").build()).execute().close()

    private fun server(requests: MutableList<String>, status: (String) -> Int): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests += "${exchange.requestMethod} ${exchange.requestURI}"
                exchange.requestHeaders.getFirst("Range")?.let { exchange.responseHeaders.add("Received-Range", it) }
                exchange.requestBody.close()
                val isProbe = exchange.requestURI.path.endsWith("/manifest.json")
                if (requests === internalRequests) {
                    internalSecrets += listOf("Authorization", "X-API-Key", "X-Komga-Api-Key", "Cookie", "token")
                        .map { exchange.requestHeaders.getFirst(it) }
                }
                if (isProbe && probeDelay > 0) Thread.sleep(probeDelay)
                val bytes = (if (isProbe) probeBody else "{}").toByteArray()
                val code = status(exchange.requestURI.path)
                if (code == 302) exchange.responseHeaders.set("Location", public + "redirect-target")
                runCatching {
                    exchange.sendResponseHeaders(code, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            start()
        }
}
