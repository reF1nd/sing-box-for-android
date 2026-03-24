package io.nekohasekai.sfa.vendor

import android.os.Build
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.ktx.unwrap
import io.nekohasekai.sfa.update.UpdateInfo
import io.nekohasekai.sfa.update.UpdateTrack
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.Closeable

class GitHubUpdateChecker : Closeable {
    companion object {
        private const val RELEASES_URL = "https://api.github.com/repos/reF1nd/sing-box-releases/releases"
        private const val METADATA_FILENAME = "SFA-version-metadata.json"

        internal fun selectRelease(
            releases: List<GitHubRelease>,
            track: UpdateTrack,
            compareSemver: (String, String) -> Boolean,
            downloadMetadata: (GitHubRelease) -> VersionMetadata?,
        ): ReleaseCandidate? {
            val candidates = releases.filter { !it.draft && (track == UpdateTrack.BETA || !it.prerelease) }
                .sortedWith { left, right ->
                    when {
                        compareSemver(left.version, right.version) -> -1
                        compareSemver(right.version, left.version) -> 1
                        else -> 0
                    }
                }
            for (release in candidates) {
                val metadata = downloadMetadata(release) ?: continue
                if (metadata.versionCode <= 0 || metadata.versionName.isBlank()) continue
                // A valid latest candidate ends the search even when already installed.
                // Do not download historical metadata on every routine update check.
                return ReleaseCandidate(release, metadata)
            }
            return null
        }

        internal fun isNewerThanCurrent(
            metadata: VersionMetadata,
            currentVersionCode: Int,
            currentVersionName: String,
            compareSemver: (String, String) -> Boolean,
        ): Boolean = if (metadata.versionCode != currentVersionCode) {
            metadata.versionCode > currentVersionCode
        } else {
            compareSemver(metadata.versionName, currentVersionName)
        }
    }

    private val client = Libbox.newHTTPClient().apply {
        modernTLS()
        keepAlive()
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(track: UpdateTrack, githubToken: String): UpdateInfo? {
        var selected = selectRelease(getReleases(track, githubToken), track, Libbox::compareSemver, ::downloadMetadata)
        if (selected == null && track == UpdateTrack.STABLE) {
            // The latest stable release may not contain a usable SFA artifact yet.
            selected = selectRelease(getReleases(UpdateTrack.BETA, githubToken), track, Libbox::compareSemver, ::downloadMetadata)
        }
        val candidate = selected ?: return null
        val release = candidate.release
        val metadata = candidate.metadata
        if (!isNewerThanCurrent(metadata, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, Libbox::compareSemver)) {
            return null
        }

        val isLegacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        val apkAsset = release.assets.find { asset ->
            asset.name.endsWith(".apk") &&
                !asset.name.contains("play") &&
                asset.name.contains("legacy-android-5") == isLegacy
        }

        return UpdateInfo(
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            downloadUrl = apkAsset?.browserDownloadUrl ?: release.htmlUrl,
            releaseUrl = release.htmlUrl,
            releaseNotes = release.body,
            isPrerelease = release.prerelease,
            fileSize = apkAsset?.size ?: 0,
        )
    }

    private fun getReleases(track: UpdateTrack, githubToken: String): List<GitHubRelease> {
        val request = client.newRequest()
        request.setURL(
            when (track) {
                UpdateTrack.STABLE -> "$RELEASES_URL/latest"
                UpdateTrack.BETA -> "$RELEASES_URL?per_page=3"
            },
        )
        request.setHeader("Accept", "application/vnd.github+json")
        val token = githubToken.trim()
        if (token.isNotEmpty()) {
            request.setHeader("Authorization", "Bearer $token")
        }
        request.setUserAgent(HTTPClient.userAgent)
        val content = request.execute().content.unwrap
        return when (track) {
            UpdateTrack.STABLE -> listOf(json.decodeFromString<GitHubRelease>(content))
            UpdateTrack.BETA -> json.decodeFromString<List<GitHubRelease>>(content)
        }
    }

    private fun downloadMetadata(release: GitHubRelease): VersionMetadata? {
        val metadataAsset = release.assets.find { it.name == METADATA_FILENAME }
            ?: return null

        val request = client.newRequest()
        request.setURL(metadataAsset.browserDownloadUrl)
        request.setUserAgent(HTTPClient.userAgent)

        val response = request.execute()
        val content = response.content.unwrap

        // Transport failures must reach the caller so automatic checks can retry.
        return try {
            json.decodeFromString<VersionMetadata>(content)
        } catch (_: SerializationException) {
            null
        }
    }

    override fun close() {
        client.close()
    }

    @Serializable
    data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GitHubAsset> = emptyList(),
    ) {
        val version: String get() = tagName.removePrefix("v")
    }

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0,
    )

    @Serializable
    data class VersionMetadata(
        @SerialName("version_code") val versionCode: Int = 0,
        @SerialName("version_name") val versionName: String = "",
    )

    internal data class ReleaseCandidate(
        val release: GitHubRelease,
        val metadata: VersionMetadata,
    )
}
