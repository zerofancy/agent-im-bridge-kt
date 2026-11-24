package top.ntutn.agent.bridge.android

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.ntutn.updater.TargetDetection
import top.ntutn.updater.UpdateArch
import top.ntutn.updater.UpdateCheckResult
import top.ntutn.updater.UpdateErrorKind
import top.ntutn.updater.UpdateOs
import top.ntutn.updater.UpdateTarget
import top.ntutn.updater.createUpdateChecker

class AppUpdatesTest {
    @Test
    fun unsupportedDevicesDoNotIssueBroadQueries() {
        assertNull(appUpdaterConfig(TargetDetection.UnsupportedPlatform))
    }

    @Test
    fun bothPublishedArchitecturesUsePublicServiceAndInstalledVersion() {
        for (arch in UpdateArch.entries) {
            val target = UpdateTarget(UpdateOs.Android, arch)
            val config = requireNotNull(appUpdaterConfig(TargetDetection.Supported(target)))
            assertEquals("https://zp.ntutn.top", config.baseUrl)
            assertEquals("agent-im-bridge-kt", config.software)
            assertEquals(target, config.target)
            assertFalse(config.allowInsecureHttp)
            assertEquals(BuildConfig.VERSION_CODE.toLong(), config.currentVersion.code)
        }
    }

    @Test
    fun sdkDetectsNewerReleaseWithoutSendingAccountCredentials() = runBlocking {
        MockWebServer().use { server ->
            val config =
                requireNotNull(
                        appUpdaterConfig(
                            TargetDetection.Supported(
                                UpdateTarget(UpdateOs.Android, UpdateArch.ARM64)
                            )
                        )
                    )
                    .copy(baseUrl = server.url("/").toString(), allowInsecureHttp = true)
            val target =
                """"software":"agent-im-bridge-kt","os":"Android","arch":"ARM64","packageType":"apk""""
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"query":{$target},"items":[{$target,"latest":{"packageType":"apk","versionCode":"${BuildConfig.VERSION_CODE + 1}","versionName":"next","downloadUrl":"https://shared-files.ntutn.top/update.apk","checksumAlgorithm":"sha256","checksumValue":"${"a".repeat(64)}"}}]}"""
                    )
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"code":"SOFTWARE_NOT_FOUND"}}""")
            )
            val checker = createUpdateChecker(config)
            try {
                val result = checker.check()
                assertTrue(result is UpdateCheckResult.Available)
                assertEquals(
                    "a".repeat(64),
                    (result as UpdateCheckResult.Available).release.checksum?.value,
                )
                val request = server.takeRequest()
                assertEquals("/api/v1/updates", request.requestUrl?.encodedPath)
                assertEquals("ARM64", request.requestUrl?.queryParameter("arch"))
                assertEquals("apk", request.requestUrl?.queryParameter("packageType"))
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
                val failure = checker.check() as UpdateCheckResult.Failed
                assertEquals(UpdateErrorKind.ServiceConfiguration, failure.error.kind)
            } finally {
                checker.close()
            }
        }
    }
}
