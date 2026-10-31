package koharia.flugel

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionAddressRouter
import koharia.connection.ConnectionRegistry
import koharia.source.kavita.KavitaConnectionProvider
import koharia.source.komga.KomgaConnectionProvider
import tachiyomi.core.common.preference.Preference
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Flugel: only addresses are provisioned by default; local build properties may pre-seed personal stores. */
fun initializeFlugelConnections(freshInstall: Boolean) {
    if (!BuildConfig.FLUGEL_PERSONAL_BUILD) return
    val connections = Injekt.get<koharia.connection.ConnectionPreferences>()
    val bootstrap = FlugelConnectionBootstrap(Injekt.get(), connections)
    fun writeProfileAddresses(
        profile: koharia.connection.LibraryConnectionProfile,
        public: String,
        internal: String,
    ) {
        sourcePreferences("source_${profile.id}").edit()
            .putString(if (profile.providerId == KomgaConnectionProvider.ID) "Address" else "address", public)
            .putString(ConnectionAddressRouter.INTERNAL_ADDRESS_KEY, internal)
            .apply()
    }
    val initializedNow = bootstrap.initialize(
        freshInstall = freshInstall || connections.getProfiles().isEmpty(),
        writeAddresses = ::writeProfileAddresses,
    )
    val profiles = connections.getProfiles()
    val komgaProfile = profiles.firstOrNull { it.providerId == KomgaConnectionProvider.ID }
    val kavitaProfile = profiles.firstOrNull { it.providerId == KavitaConnectionProvider.ID }

    if (komgaProfile != null) {
        val store = sourcePreferences("source_${komgaProfile.id}")
        if (store.getString("Address", "").isNullOrBlank()) {
            writeProfileAddresses(komgaProfile, "https://manga.gokalpgoksu.com", "http://192.168.0.15:8296")
        }
        if (!store.contains("Default libraries")) {
            store.edit().putStringSet("Default libraries", setOf("0QE7VDJHCE943")).apply()
        }
        val classification = Injekt.get<koharia.source.komga.KomgaLibraryClassificationManager>()
        if (classification.getLibraries(komgaProfile.id).isEmpty()) {
            classification.updateLibraries(
                komgaProfile.id,
                listOf(
                    koharia.komga.api.dto.LibraryDto(id = "0QE7VDJHCE943", name = "Manga"),
                    koharia.komga.api.dto.LibraryDto(id = "0QE7TV3FRE6R5", name = "Light Novel"),
                ),
            )
            classification.setKind(
                komgaProfile.id,
                "0QE7VDJHCE943",
                koharia.source.komga.KomgaLibraryKind.COMIC,
            )
            classification.setKind(
                komgaProfile.id,
                "0QE7TV3FRE6R5",
                koharia.source.komga.KomgaLibraryKind.BOOK,
            )
        }
        val preconfiguredKomgaKey = BuildConfig.FLUGEL_KOMGA_API_KEY
        val hasKomgaCreds = if (store.getString("AuthMode", "Credentials") == "ApiKey") {
            !store.getString("API key", store.getString("Api key", "")).isNullOrBlank()
        } else {
            !store.getString("Username", "").isNullOrBlank() && !store.getString("Password", "").isNullOrBlank()
        }
        if (!hasKomgaCreds && preconfiguredKomgaKey.isNotBlank()) {
            store.edit()
                .putString("AuthMode", "ApiKey")
                .putString("API key", preconfiguredKomgaKey)
                .apply()
        }
    }

    if (kavitaProfile != null) {
        val prefs = koharia.source.kavita.KavitaPreferences(kavitaProfile.id)
        val preconfiguredKavitaKey = BuildConfig.FLUGEL_KAVITA_API_KEY
        if (prefs.key.isBlank() && preconfiguredKavitaKey.isNotBlank()) {
            prefs.save(
                address = "https://ln.gokalpgoksu.com",
                key = preconfiguredKavitaKey,
                account = koharia.kavita.KavitaAccount(
                    id = 1L,
                    username = "flugel",
                    roles = listOf("Admin", "Login", "Download", "Change Password", "Bookmark"),
                    kavitaVersion = "0.9.1.4",
                ),
            )
            prefs.internalAddress = "http://192.168.0.15:8295"
            if (prefs.mediaId == 0L) {
                prefs.mediaId = 2L
            }
        }
    }

    val allConfigured = profiles.isNotEmpty() && profiles.all { profile ->
        val store = sourcePreferences("source_${profile.id}")
        when (profile.providerId) {
            KomgaConnectionProvider.ID -> if (store.getString("AuthMode", "Credentials") == "ApiKey") {
                !store.getString("API key", store.getString("Api key", "")).isNullOrBlank()
            } else {
                !store.getString("Username", "").isNullOrBlank() && !store.getString("Password", "").isNullOrBlank()
            }
            KavitaConnectionProvider.ID -> !store.getString(Preference.privateKey("kavita_key"), "").isNullOrBlank()
            else -> true
        }
    }
    if (allConfigured) {
        bootstrap.setupRequested.set(false)
    }
    if (initializedNow || allConfigured) {
        // Flugel: the personal credential screen replaces generic add-a-server onboarding.
        Injekt.get<BasePreferences>().shownOnboardingFlow.set(true)
    }
}

/** Flugel: prompt once after provisioning and reuse the providers' normal credential forms. */
@Composable
fun FlugelFirstRunSetup() {
    if (!BuildConfig.FLUGEL_PERSONAL_BUILD) return
    val navigator = LocalNavigator.currentOrThrow
    val bootstrap = remember { FlugelConnectionBootstrap(Injekt.get(), Injekt.get()) }
    LaunchedEffect(Unit) {
        if (bootstrap.setupRequested.get()) navigator.push(FlugelCredentialsScreen())
    }
}

private class FlugelCredentialsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val bootstrap = remember { FlugelConnectionBootstrap(Injekt.get(), Injekt.get()) }
        val registry = remember { Injekt.get<ConnectionRegistry>() }
        val profiles = remember { bootstrap.setupProfiles() }
        val stores = remember(profiles) { profiles.associate { it.id to sourcePreferences("source_${it.id}") } }
        var configured by remember { mutableStateOf(emptySet<Long>()) }
        DisposableEffect(stores) {
            fun refresh() {
                configured = profiles.filter { profile ->
                    val store = stores.getValue(profile.id)
                    when (profile.providerId) {
                        KomgaConnectionProvider.ID -> hasKomgaCredentials(store)
                        KavitaConnectionProvider.ID -> !store.getString(
                            Preference.privateKey("kavita_key"),
                            "",
                        ).isNullOrBlank()
                        else -> false
                    }
                }.mapTo(mutableSetOf()) { it.id }
            }
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
            stores.values.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
            refresh()
            onDispose { stores.values.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) } }
        }
        fun finish() {
            bootstrap.setupRequested.set(false)
            navigator.pop()
        }
        BackHandler { finish() }
        Scaffold(topBar = { AppBar(title = stringResource(MR.strings.flugel_setup_title)) }) { padding ->
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(MR.strings.flugel_setup_description),
                    modifier = Modifier.padding(16.dp),
                )
                profiles.forEach { profile ->
                    TextPreferenceWidget(
                        title = profile.name,
                        subtitle = stringResource(
                            if (profile.id in
                                configured
                            ) {
                                MR.strings.flugel_setup_configured
                            } else {
                                MR.strings.flugel_setup_sign_in
                            },
                        ),
                        onPreferenceClick = {
                            registry.provider(profile.providerId)?.createSettingsScreen(profile)?.let(navigator::push)
                        },
                    )
                }
                if (profiles.all { it.id in configured }) {
                    Button(onClick = ::finish, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(MR.strings.action_continue))
                    }
                } else {
                    TextButton(onClick = ::finish, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(MR.strings.flugel_setup_later))
                    }
                }
            }
        }
    }

    private fun hasKomgaCredentials(store: SharedPreferences): Boolean =
        if (store.getString("AuthMode", "Credentials") == "ApiKey") {
            !store.getString("API key", store.getString("Api key", "")).isNullOrBlank()
        } else {
            !store.getString("Username", "").isNullOrBlank() && !store.getString("Password", "").isNullOrBlank()
        }
}
