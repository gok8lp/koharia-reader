package eu.kanade.tachiyomi.ui.library

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import koharia.connection.ConnectionBrowseAdapter
import koharia.connection.ConnectionBrowseScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryContentScope
import koharia.connection.NO_ACTIVE_CONNECTION
import koharia.connection.ui.LibraryConnectionProfilesScreen
import koharia.connection.ui.LibraryConnectionSetupPrompt
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.motion.rememberEInkAwareAnimatedVectorPainter
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.jvm.Transient

sealed class ConnectionLibraryTab(
    private val libraryScope: LibraryContentScope,
    private val tabIndex: UShort,
) : Tab {

    // Voyager serializes Tab instances when the host Activity is stopped. The
    // browse screen owns coroutine channels and must therefore stay runtime-only.
    @Transient
    @Volatile
    private var runtimeBrowseScreen: ConnectionBrowseScreen? = null

    /** Bu sekmenin gosterecegi baglanti. Kitaplar sekmesi ayri bir baglantiya baglanabilir (Flugel). */
    protected open fun effectiveConnectionId(active: Long, book: Long): Long = active

    private fun currentConnectionId(): Long {
        val prefs = Injekt.get<ConnectionPreferences>()
        return effectiveConnectionId(prefs.activeConnectionId.get(), prefs.bookConnectionId.get())
    }

    // Scope the cached screen was created with; split mode changes it without changing the source.
    @Transient
    @Volatile
    private var runtimeBrowseScope: LibraryContentScope? = null

    /** Ayri baglanti modunda her sekme kendi baglantisinin tum icerigini gosterir. */
    private fun effectiveScope(
        active: Long = Injekt.get<ConnectionPreferences>().activeConnectionId.get(),
        book: Long = Injekt.get<ConnectionPreferences>().bookConnectionId.get(),
    ): LibraryContentScope {
        val split = Injekt.get<ConnectionPreferences>().splitBookConnectionId(active, book) != null
        return if (split) LibraryContentScope.ALL else libraryScope
    }

    private fun browseScreen(
        activeConnectionId: Long = currentConnectionId(),
        scope: LibraryContentScope = effectiveScope(),
    ): ConnectionBrowseScreen? {
        if (activeConnectionId == NO_ACTIVE_CONNECTION) return null
        fun ConnectionBrowseScreen.matches() = sourceId == activeConnectionId && runtimeBrowseScope == scope
        runtimeBrowseScreen?.takeIf { it.matches() }?.let { return it }
        return synchronized(this) {
            runtimeBrowseScreen
                ?.takeIf { it.matches() }
                ?: newScreen(activeConnectionId, scope)?.also {
                    runtimeBrowseScreen = it
                    runtimeBrowseScope = scope
                }
        }
    }

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val title = when (libraryScope) {
                LibraryContentScope.ALL -> stringResource(MR.strings.label_library)
                LibraryContentScope.COMIC -> stringResource(MR.strings.label_comics)
                LibraryContentScope.BOOK -> stringResource(MR.strings.label_books)
            }
            val icon = if (libraryScope == LibraryContentScope.BOOK) {
                painterResource(R.drawable.ic_book_24dp)
            } else {
                val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
                rememberEInkAwareAnimatedVectorPainter(image, isSelected, Icons.Outlined.CollectionsBookmark)
            }
            return TabOptions(
                index = tabIndex,
                title = title,
                icon = icon,
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        browseScreen()?.takeIf { it.refreshOnReselect }?.refresh()
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val connectionPreferences = remember { Injekt.get<ConnectionPreferences>() }
        val activeConnectionId by connectionPreferences.activeConnectionId.collectAsState()
        val bookConnectionId by connectionPreferences.bookConnectionId.collectAsState()
        val activeServerId = effectiveConnectionId(activeConnectionId, bookConnectionId)
        if (activeServerId == NO_ACTIVE_CONNECTION) {
            Scaffold { contentPadding ->
                LibraryConnectionSetupPrompt(
                    onConfigureConnection = { navigator.push(LibraryConnectionProfilesScreen(openAddDialog = true)) },
                    modifier = Modifier.padding(contentPadding),
                )
            }
            return
        }
        // Keep the same screen instance while opening details or a reader. Its ScreenModel owns
        // the selected shelf, filters, search, and paging cache.
        val browseScope = effectiveScope(activeConnectionId, bookConnectionId)
        val activeScreen = remember(activeServerId, browseScope) { browseScreen(activeServerId, browseScope) }
        if (activeScreen == null) {
            tachiyomi.presentation.core.screens.EmptyScreen(
                stringRes = MR.strings.connection_unavailable,
            )
            return
        }
        val isSelected = LocalTabNavigator.current.current.key == key
        var hasEntered by remember { mutableStateOf(false) }

        LaunchedEffect(isSelected) {
            if (isSelected) {
                if (hasEntered) {
                    browseScreen()?.takeIf { it.refreshOnReselect }?.refresh()
                } else {
                    hasEntered = true
                }
            }
        }

        activeScreen.Content()
    }

    suspend fun search(query: String) = browseScreen()?.search(query)

    suspend fun searchGenre(name: String) = browseScreen()?.searchGenre(name)

    private fun clearRuntimeState() {
        val (cachedSourceId, cachedScope) = synchronized(this) {
            (runtimeBrowseScreen?.sourceId to runtimeBrowseScope).also {
                runtimeBrowseScreen = null
                runtimeBrowseScope = null
            }
        }
        val activeSourceId = currentConnectionId()
        val scopes = setOfNotNull(libraryScope, cachedScope, effectiveScope())
        setOfNotNull(
            cachedSourceId,
            activeSourceId.takeUnless { it == NO_ACTIVE_CONNECTION },
        ).forEach { sourceId ->
            val adapter = Injekt.get<SourceManager>().get(sourceId) as? ConnectionBrowseAdapter
            scopes.forEach { adapter?.clearBrowseSession(it) }
        }
    }

    private fun newScreen(sourceId: Long, scope: LibraryContentScope): ConnectionBrowseScreen? {
        val source = Injekt.get<SourceManager>().get(sourceId) ?: return null
        return (source as? ConnectionBrowseAdapter)?.createBrowseScreen(
            scope = scope,
            listingQuery = null,
            showNavigationUp = false,
        )
    }

    companion object {
        fun clearAllRuntimeState() {
            listOf<ConnectionLibraryTab>(LibraryTab, ComicsTab, BooksTab)
                .forEach(ConnectionLibraryTab::clearRuntimeState)
        }
    }
}

data object LibraryTab : ConnectionLibraryTab(LibraryContentScope.ALL, 0u)

data object ComicsTab : ConnectionLibraryTab(LibraryContentScope.COMIC, 0u)

data object BooksTab : ConnectionLibraryTab(LibraryContentScope.BOOK, 1u) {
    override fun effectiveConnectionId(active: Long, book: Long): Long =
        Injekt.get<ConnectionPreferences>().splitBookConnectionId(active, book) ?: active
}
