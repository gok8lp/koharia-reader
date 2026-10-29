package eu.kanade.tachiyomi.data.updater

import android.app.Application
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

class AppUpdateChecker {

    private val getApplicationRelease: GetApplicationRelease by injectLazy()
    private val application: Application by injectLazy()

    suspend fun checkForUpdate(forceCheck: Boolean = false): GetApplicationRelease.Result {
        // Disable app update checks for older Android versions that we're going to drop support for
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        //     return GetApplicationRelease.Result.OsTooOld
        // }

        return withIOContext {
            // Flugel: check our own manifest on every launch, independently of upstream releases.
            if (BuildConfig.FLUGEL_PERSONAL_BUILD) {
                return@withIOContext checkPersonalRelease()
            }
            val result = getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFossBuildType,
                    isPreviewBuildType,
                    BuildConfig.COMMIT_COUNT.toInt(),
                    BuildConfig.VERSION_NAME,
                    GITHUB_REPO,
                    forceCheck,
                ),
            )

            result
        }
    }

    private suspend fun checkPersonalRelease(): GetApplicationRelease.Result {
        val manifestUrl = BuildConfig.FLUGEL_UPDATE_MANIFEST_URL
        if (manifestUrl.isBlank()) return GetApplicationRelease.Result.NoNewUpdate()
        FlugelUpdateManifest.requireSecureUrl(manifestUrl)
        val request = Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").build()
        val manifest = manifestClient.newCall(request).await().use { response ->
            check(response.isSuccessful) { "Update manifest request failed: ${response.code}" }
            val body = response.peekBody(64 * 1024L + 1)
            check(body.contentLength() <= 64 * 1024) { "Update manifest is too large" }
            FlugelUpdateManifest.parse(body.string())
        }
        val release = Release(
            version = manifest.versionName?.takeIf(String::isNotBlank) ?: manifest.versionCode.toString(),
            info = application.stringResource(MR.strings.flugel_update_available),
            releaseLink = manifestUrl,
            downloadLink = manifest.url,
            expectedSize = manifest.size,
            expectedSha256 = manifest.sha256,
        )
        return if (manifest.versionCode > BuildConfig.VERSION_CODE) {
            GetApplicationRelease.Result.NewUpdate(release)
        } else {
            GetApplicationRelease.Result.NoNewUpdate(release)
        }
    }

    private companion object {
        // Flugel: metadata requests have no application cookies, auth interceptors or redirects.
        val manifestClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

val GITHUB_REPO: String by lazy {
    "Mister-album/Koharia"
}
