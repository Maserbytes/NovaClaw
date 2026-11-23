package ai.novaclaw.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Captures real screen pixels via the AccessibilityService screenshot API. */
object ScreenCapture {

    suspend fun capture(context: Context): String? {
        val service = NovaAccessibilityService.instance ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            val executor = Executors.newSingleThreadExecutor()
            if (cont.isActive) {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            // Copy before closing the HardwareBuffer — the wrapped
                            // Bitmap becomes invalid once the buffer is released.
                            val wrapped = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer, screenshot.colorSpace)
                            val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            screenshot.hardwareBuffer.close()
                            if (copy != null) {
                                val file = File(context.cacheDir, "nova_screen.png")
                                FileOutputStream(file).use { copy.compress(Bitmap.CompressFormat.PNG, 85, it) }
                                if (cont.isActive) cont.resume(file.absolutePath)
                            } else if (cont.isActive) cont.resume(null)
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resume(null)
                        } finally {
                            executor.shutdown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        executor.shutdown()
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        }
    }
}
