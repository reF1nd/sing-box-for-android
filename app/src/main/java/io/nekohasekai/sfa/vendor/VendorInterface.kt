package io.nekohasekai.sfa.vendor

import android.app.Activity
import androidx.camera.core.ImageAnalysis
import io.nekohasekai.sfa.compose.screen.qrscan.QRCodeCropArea
import io.nekohasekai.sfa.update.UpdateInfo

interface VendorInterface {
    fun checkUpdate(activity: Activity, byUser: Boolean)

    fun createQRCodeAnalyzer(
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit,
        onCropArea: ((QRCodeCropArea?) -> Unit)? = null,
    ): ImageAnalysis.Analyzer?

    fun isPerAppProxyAvailable(): Boolean = true

    val hasCustomUpdate: Boolean get() = false

    fun checkUpdateAsync(): UpdateInfo? = null

    fun scheduleAutoUpdate() {}

    suspend fun verifySilentInstallMethod(method: String): Boolean = false

    suspend fun downloadAndInstall(context: android.content.Context, downloadUrl: String): Unit = throw UnsupportedOperationException("Not supported in this flavor")
}
