package io.nekohasekai.sfa.vendor

import io.nekohasekai.sfa.update.UpdateTrack
import io.nekohasekai.sfa.vendor.GitHubUpdateChecker.GitHubRelease
import io.nekohasekai.sfa.vendor.GitHubUpdateChecker.VersionMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GitHubUpdateCheckerTest {
    // Ordering of actual published versions; the native semver implementation is
    // injected so these tests exercise update policy without loading Android JNI.
    private val versions = listOf(
        "1.14.0-reF1nd",
        "1.14.0-reF1nd.1",
        "1.14.2-reF1nd",
        "1.15.0-alpha.6-reF1nd",
        "1.15.0-alpha.8-reF1nd",
    )
    private val compareSemver: (String, String) -> Boolean = { left, right ->
        require(left in versions && right in versions)
        versions.indexOf(left) > versions.indexOf(right)
    }
    private val stable = GitHubRelease(tagName = "v1.14.2-reF1nd")
    private val testing = GitHubRelease(tagName = "v1.15.0-alpha.8-reF1nd", prerelease = true)
    private val olderTesting = GitHubRelease(tagName = "v1.15.0-alpha.6-reF1nd", prerelease = true)

    @Test
    fun betaChoosesHighestVersionInsteadOfMostRecentlyPublished() {
        val downloads = mutableListOf<String>()
        val selected = GitHubUpdateChecker.selectRelease(
            listOf(stable, testing, olderTesting),
            UpdateTrack.BETA,
            compareSemver,
        ) {
            downloads += it.tagName
            VersionMetadata(3005, it.version)
        }
        assertEquals(testing, selected?.release)
        assertEquals(listOf(testing.tagName), downloads)
    }

    @Test
    fun stableFallbackExcludesPrereleasesAndDrafts() {
        val selected = GitHubUpdateChecker.selectRelease(
            listOf(testing, olderTesting.copy(prerelease = false, draft = true), stable),
            UpdateTrack.STABLE,
            compareSemver,
        ) { VersionMetadata(2056, it.version) }
        assertEquals(stable, selected?.release)
    }

    @Test
    fun betaAlsoAcceptsStableReleases() {
        val selected = GitHubUpdateChecker.selectRelease(listOf(stable), UpdateTrack.BETA, compareSemver) {
            VersionMetadata(2056, it.version)
        }
        assertEquals(stable, selected?.release)
    }

    @Test
    fun missingOrInvalidMetadataFallsBackToNextCandidate() {
        val downloads = mutableListOf<String>()
        val selected = GitHubUpdateChecker.selectRelease(
            listOf(stable, testing, olderTesting),
            UpdateTrack.BETA,
            compareSemver,
        ) {
            downloads += it.tagName
            when (it) {
                testing -> null
                olderTesting -> VersionMetadata()
                else -> VersionMetadata(2056, it.version)
            }
        }
        assertEquals(stable, selected?.release)
        assertEquals(listOf(testing.tagName, olderTesting.tagName, stable.tagName), downloads)
    }

    @Test
    fun noUsableMetadataReturnsNoCandidate() {
        assertNull(GitHubUpdateChecker.selectRelease(listOf(stable), UpdateTrack.STABLE, compareSemver) { null })
    }

    @Test(expected = IOException::class)
    fun networkFailurePropagatesForRetry() {
        GitHubUpdateChecker.selectRelease(listOf(testing, stable), UpdateTrack.BETA, compareSemver) {
            throw IOException("network unavailable")
        }
    }

    @Test
    fun alreadyInstalledVersionDoesNotDownloadHistoricalMetadata() {
        var downloads = 0
        val selected = GitHubUpdateChecker.selectRelease(
            listOf(stable, testing, olderTesting),
            UpdateTrack.BETA,
            compareSemver,
        ) {
            downloads++
            VersionMetadata(3005, it.version)
        }!!
        assertFalse(GitHubUpdateChecker.isNewerThanCurrent(selected.metadata, 3005, testing.version, compareSemver))
        assertEquals(1, downloads)
    }

    @Test
    fun higherCodeUpdatesEvenWithUnchangedOrLowerVersionName() {
        assertTrue(GitHubUpdateChecker.isNewerThanCurrent(VersionMetadata(3006, testing.version), 3005, testing.version, compareSemver))
        assertTrue(GitHubUpdateChecker.isNewerThanCurrent(VersionMetadata(3006, olderTesting.version), 3005, testing.version, compareSemver))
    }

    @Test
    fun lowerCodeCannotUpdateEvenWithHigherVersionName() {
        assertFalse(GitHubUpdateChecker.isNewerThanCurrent(VersionMetadata(3004, testing.version), 3005, olderTesting.version, compareSemver))
    }

    @Test
    fun equalCodeUsesMetadataVersionName() {
        assertTrue(GitHubUpdateChecker.isNewerThanCurrent(VersionMetadata(2054, "1.14.0-reF1nd.1"), 2054, "1.14.0-reF1nd", compareSemver))
        assertFalse(GitHubUpdateChecker.isNewerThanCurrent(VersionMetadata(2054, "1.14.0-reF1nd"), 2054, "1.14.0-reF1nd.1", compareSemver))
    }
}
