package koharia.flugel

import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryConnectionProfile
import koharia.connection.MutableTestPreferenceStore
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Flugel: provisioning is one-time and never replaces an existing or deliberately empty inventory. */
class FlugelConnectionBootstrapTest {
    private val store = MutableTestPreferenceStore()
    private val connections = ConnectionPreferences(store, Json)
    private val bootstrap = FlugelConnectionBootstrap(store, connections)

    @Test
    fun `fresh install receives two address-only profiles with separate books connection`() {
        val addresses = mutableMapOf<String, Pair<String, String>>()
        assertTrue(
            bootstrap.initialize(true) { profile, public, internal ->
                addresses[profile.providerId] = public to internal
            },
        )

        val profiles = connections.getProfiles()
        assertEquals(listOf("komga", "kavita"), profiles.map { it.providerId })
        assertEquals(profiles[0].id, connections.activeConnectionId.get())
        assertEquals(profiles[1].id, connections.bookConnectionId.get())
        assertEquals("https://manga.gokalpgoksu.com" to "http://192.168.0.15:8296", addresses["komga"])
        assertEquals("https://ln.gokalpgoksu.com" to "http://192.168.0.15:8295", addresses["kavita"])
        assertEquals(profiles, bootstrap.setupProfiles())
        assertTrue(bootstrap.setupRequested.get())
    }

    @Test
    fun `later launches and removing all profiles do not re-seed`() {
        bootstrap.initialize(true) { _, _, _ -> }
        bootstrap.setupRequested.set(false)
        connections.setProfiles(emptyList())

        val reopened = FlugelConnectionBootstrap(store, connections)
        assertFalse(reopened.initialize(true) { _, _, _ -> error("Must not write") })
        assertTrue(connections.getProfiles().isEmpty())
        assertFalse(reopened.setupRequested.get())
    }

    @Test
    fun `migrated or restored profiles are retained even during first app startup`() {
        val existing = LibraryConnectionProfile(42, "komga", "Existing server")
        connections.setProfiles(listOf(existing))
        assertFalse(bootstrap.initialize(true) { _, _, _ -> error("Must not write") })
        assertEquals(listOf(existing), connections.getProfiles())
        assertEquals(42L, connections.activeConnectionId.get())
        assertFalse(bootstrap.setupRequested.get())
    }

    @Test
    fun `existing empty installation keeps its empty inventory`() {
        assertFalse(bootstrap.initialize(false) { _, _, _ -> error("Must not write") })
        assertTrue(connections.getProfiles().isEmpty())
        assertFalse(bootstrap.setupRequested.get())
    }

    @Test
    fun `interrupted address writes are retryable before profiles become visible`() {
        runCatching { bootstrap.initialize(true) { _, _, _ -> error("Interrupted") } }
        assertTrue(connections.getProfiles().isEmpty())
        assertTrue(bootstrap.initialize(true) { _, _, _ -> })
        assertEquals(2, connections.getProfiles().size)
    }
}
