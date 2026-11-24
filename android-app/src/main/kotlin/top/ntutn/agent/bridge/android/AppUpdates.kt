package top.ntutn.agent.bridge.android

import android.app.Activity
import android.app.Application
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import top.ntutn.updater.TargetDetection
import top.ntutn.updater.UpdateVersion
import top.ntutn.updater.UpdaterConfig
import top.ntutn.updater.compose.AndroidUpdateHost
import top.ntutn.updater.compose.CheckTrigger
import top.ntutn.updater.compose.UpdateController
import top.ntutn.updater.compose.UpdateUiState
import top.ntutn.updater.createAndroidBrowserOpener
import top.ntutn.updater.createAndroidUpdateDownloader
import top.ntutn.updater.createUpdateChecker
import top.ntutn.updater.detectUpdateTarget

internal fun appUpdaterConfig(detection: TargetDetection): UpdaterConfig? =
    when (detection) {
        is TargetDetection.Supported -> {
            UpdaterConfig(
                baseUrl = "https://zp.ntutn.top",
                software = "agent-im-bridge-kt",
                currentVersion =
                    UpdateVersion(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME),
                target = detection.target,
                packageType = "apk",
            )
        }
        TargetDetection.UnsupportedPlatform -> {
            null
        }
    }

/** Owns only application-context resources, so downloads survive Activity recreation. */
internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val config = appUpdaterConfig(detectUpdateTarget())
    private val checker = config?.let { createUpdateChecker(it) }
    private val downloader = config?.let { createAndroidUpdateDownloader(application) }
    val controller =
        checker?.let {
            UpdateController(
                it,
                createAndroidBrowserOpener(application),
                requireNotNull(downloader),
                viewModelScope,
            )
        }
    private var checkedOnStart = false

    fun checkOnStart() {
        if (!checkedOnStart) {
            checkedOnStart = true
            controller?.check(CheckTrigger.Silent)
        }
    }

    override fun onCleared() {
        try {
            controller?.dispose()
        } finally {
            try {
                downloader?.close()
            } finally {
                checker?.close()
            }
        }
    }
}

internal val LocalAppUpdates = staticCompositionLocalOf<UpdateController?> { null }

@Composable
internal fun AppUpdateButton() {
    val controller = LocalAppUpdates.current
    if (controller == null) {
        Text("版本 ${BuildConfig.VERSION_NAME} · 此设备不支持应用内更新")
    } else {
        val state by controller.state.collectAsState()
        TextButton(
            onClick = { controller.check(CheckTrigger.Manual) },
            enabled = state !is UpdateUiState.Checking,
            modifier = Modifier.testTag("bridge_check_update"),
        ) {
            Text("版本 ${BuildConfig.VERSION_NAME} · 检查更新")
        }
    }
}

@Composable
internal fun AppUpdateHost(controller: UpdateController?, activity: Activity, foreground: Boolean) {
    if (controller != null) {
        androidx.compose.material3.MaterialTheme {
            AndroidUpdateHost(controller = controller, activity = activity, foreground = foreground)
        }
    }
}
