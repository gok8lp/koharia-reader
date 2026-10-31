package eu.kanade.presentation.more

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.ui.more.DownloadQueueState
import tachiyomi.core.common.DocumentationUrls
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MoreScreen(
    showOfflineControls: Boolean,
    downloadQueueStateProvider: () -> DownloadQueueState,
    downloadedOnly: Boolean,
    downloadedOnlyEnabled: Boolean,
    settingsEnabled: Boolean,
    scopedSettingsBlockedReason: String?,
    onDownloadedOnlyChange: (Boolean) -> Unit,
    onClickDownloadQueue: () -> Unit,
    onClickServerDownloads: (() -> Unit)? = null,
    onClickSuwayomiBrowse: (() -> Unit)? = null,
    onClickSuwayomiDownloads: (() -> Unit)? = null,
    onClickSuwayomiLibrary: (() -> Unit)? = null,
    onClickStats: () -> Unit,
    onClickDataAndStorage: () -> Unit,
    onClickSettings: () -> Unit,
    onClickServerManagement: () -> Unit,
    onClickSupport: () -> Unit,
    onClickAbout: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Scaffold { contentPadding ->
        ScrollbarLazyColumn(contentPadding = contentPadding) {
            item {
                LogoHeader(
                    iconPadding = PaddingValues(vertical = 32.dp),
                )
            }
            if (showOfflineControls) {
                item {
                    SwitchPreferenceWidget(
                        title = stringResource(MR.strings.komga_label_cached_only),
                        subtitle = scopedSettingsBlockedReason ?: stringResource(MR.strings.komga_cached_only_summary),
                        icon = Icons.Outlined.CloudOff,
                        checked = downloadedOnly,
                        enabled = downloadedOnlyEnabled,
                        onCheckedChanged = onDownloadedOnlyChange,
                    )
                }

                item { HorizontalDivider() }

                item {
                    val downloadQueueState = downloadQueueStateProvider()
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.komga_label_offline_cache_queue),
                        subtitle = when (downloadQueueState) {
                            DownloadQueueState.Stopped -> null
                            is DownloadQueueState.Paused -> {
                                val pending = downloadQueueState.pending
                                if (pending == 0) {
                                    stringResource(MR.strings.paused)
                                } else {
                                    "${stringResource(MR.strings.paused)} • ${
                                        pluralStringResource(
                                            MR.plurals.download_queue_summary,
                                            count = pending,
                                            pending,
                                        )
                                    }"
                                }
                            }
                            is DownloadQueueState.Downloading -> {
                                val pending = downloadQueueState.pending
                                pluralStringResource(MR.plurals.download_queue_summary, count = pending, pending)
                            }
                        },
                        icon = Icons.Outlined.GetApp,
                        onPreferenceClick = onClickDownloadQueue,
                    )
                }
            }
            if (onClickServerDownloads != null) {
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.server_download_queue),
                        icon = Icons.Outlined.Storage,
                        onPreferenceClick = onClickServerDownloads,
                    )
                }
            }
            // Flugel: etkin bağlantı Komga olsa bile Suwayomi araması, indirme kuyruğu ve kütüphanesi
            if (onClickSuwayomiBrowse != null) {
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.flugel_suwayomi_ara),
                        subtitle = stringResource(MR.strings.flugel_suwayomi_ara_aciklama),
                        icon = Icons.Outlined.TravelExplore,
                        onPreferenceClick = onClickSuwayomiBrowse,
                    )
                }
            }
            if (onClickSuwayomiDownloads != null) {
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.flugel_suwayomi_indirmeler),
                        subtitle = stringResource(MR.strings.flugel_suwayomi_indirmeler_aciklama),
                        icon = Icons.Outlined.Downloading,
                        onPreferenceClick = onClickSuwayomiDownloads,
                    )
                }
            }
            if (onClickSuwayomiLibrary != null) {
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.flugel_suwayomi_kutuphane),
                        subtitle = stringResource(MR.strings.flugel_suwayomi_kutuphane_aciklama),
                        icon = Icons.Outlined.CollectionsBookmark,
                        onPreferenceClick = onClickSuwayomiLibrary,
                    )
                }
            }
            // Flugel: sunucudaki indirici ve yukleme sayfalari (sifreli, tarayicida acilir)
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.flugel_manga_indir),
                    subtitle = stringResource(MR.strings.flugel_manga_indir_aciklama),
                    icon = Icons.Outlined.CloudDownload,
                    onPreferenceClick = { uriHandler.openUri("https://indir.gokalpgoksu.com/") },
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.flugel_kutuphaneye_ekle),
                    subtitle = stringResource(MR.strings.flugel_kutuphaneye_ekle_aciklama),
                    icon = Icons.Outlined.CloudUpload,
                    onPreferenceClick = { uriHandler.openUri("https://gokalpgoksu.com/yukle/") },
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_stats),
                    icon = Icons.Outlined.QueryStats,
                    onPreferenceClick = onClickStats,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_data_storage),
                    icon = Icons.Outlined.Storage,
                    onPreferenceClick = onClickDataAndStorage,
                )
            }

            item { HorizontalDivider() }

            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.pref_server_management),
                    icon = Icons.Outlined.Router,
                    onPreferenceClick = onClickServerManagement,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_settings),
                    icon = Icons.Outlined.Settings,
                    subtitle = scopedSettingsBlockedReason,
                    enabled = settingsEnabled,
                    onPreferenceClick = onClickSettings.takeIf { settingsEnabled },
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_support_us),
                    icon = Icons.Outlined.VolunteerActivism,
                    onPreferenceClick = onClickSupport,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.pref_category_about),
                    icon = Icons.Outlined.Info,
                    onPreferenceClick = onClickAbout,
                )
            }
            item {
                TextPreferenceWidget(
                    title = stringResource(MR.strings.label_help),
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    onPreferenceClick = { uriHandler.openUri(DocumentationUrls.feedback(context)) },
                )
            }
        }
    }
}
