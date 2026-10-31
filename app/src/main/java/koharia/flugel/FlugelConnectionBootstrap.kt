package koharia.flugel

import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryConnectionProfile
import koharia.source.kavita.KavitaConnectionProvider
import koharia.source.komga.KomgaConnectionProvider
import tachiyomi.core.common.preference.PreferenceStore

/** Flugel: one-time, credential-free defaults; existing installations retain their inventory. */
class FlugelConnectionBootstrap(
    private val store: PreferenceStore,
    private val connections: ConnectionPreferences,
) {
    private val initialized = store.getBoolean("flugel_profiles_initialized", false)
    val setupRequested = store.getBoolean("flugel_credentials_setup_requested", false)
    private val profileIds = store.getStringSet("flugel_default_profile_ids", emptySet())

    @Synchronized
    fun initialize(
        freshInstall: Boolean,
        writeAddresses: (LibraryConnectionProfile, String, String) -> Unit,
    ): Boolean {
        if (initialized.get()) return false
        if (!freshInstall || connections.getProfiles().isNotEmpty()) {
            initialized.set(true)
            return false
        }
        val profiles = listOf(
            LibraryConnectionProfile(connections.allocateConnectionId(), KomgaConnectionProvider.ID, "Flugel Komga"),
            LibraryConnectionProfile(connections.allocateConnectionId(), KavitaConnectionProvider.ID, "Flugel Kavita"),
        )
        writeAddresses(profiles[0], KOMGA_PUBLIC_URL, KOMGA_INTERNAL_URL)
        writeAddresses(profiles[1], KAVITA_PUBLIC_URL, KAVITA_INTERNAL_URL)
        connections.setProfiles(profiles)
        connections.activeConnectionId.set(profiles[0].id)
        connections.bookConnectionId.set(profiles[1].id)
        profileIds.set(profiles.mapTo(linkedSetOf()) { it.id.toString() })
        setupRequested.set(true)
        initialized.set(true)
        return true
    }

    fun setupProfiles(): List<LibraryConnectionProfile> = connections.getProfiles()
        .filter { it.id.toString() in profileIds.get() }

    companion object {
        const val KOMGA_PUBLIC_URL = "https://flugelserver.tail42f1f4.ts.net:8443"
        const val KOMGA_INTERNAL_URL = "http://192.168.0.15:8296"
        const val KAVITA_PUBLIC_URL = "https://flugelserver.tail42f1f4.ts.net:10000"
        const val KAVITA_INTERNAL_URL = "http://192.168.0.15:8295"
    }
}
