package ai.novaclaw.model

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val downloaded: Long,
    val total: Long,
    val percent: Int,
    val status: String,
)

object ModelDownloader {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        context: Context,
        model: ModelInfo,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val lock = locks.getOrPut(model.id) { Mutex() }
        if (!lock.tryLock()) {
            return@withContext Result.failure(IllegalStateException("This model is already downloading."))
        }
        try {
            downloadLocked(context, model, onProgress)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun downloadLocked(
        context: Context,
        model: ModelInfo,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<String> {
        val dir = runCatching { ModelCatalog.modelsDir(context) }
            .getOrElse { return Result.failure(it) }
        val target = File(dir, model.fileName)
        val temp = File(dir, "${model.fileName}.downloading")

        if (ModelCatalog.isInstalled(context, model)) {
            return Result.success(ModelCatalog.path(context, model))
        }

        val tolerance = 16L * 1024 * 1024
        if (temp.exists() && temp.length() > model.sizeBytes + tolerance) temp.delete()
        if (temp.isFile && temp.length() == model.sizeBytes) {
            return runCatching { finalize(context, model, temp, target, onProgress) }
        }

        val available = ModelCatalog.freeSpaceBytes(context)
        if (available < model.sizeBytes - temp.length()) {
            return Result.failure(
                IllegalStateException("Not enough storage: need %.1f GB, have %.1f GB".format(
                    (model.sizeBytes - temp.length()) / 1e9, available / 1e9))
            )
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "novaclaw:model_download")
        wakeLock?.acquire(45 * 60 * 1000L)

        return try {
            val existing = if (temp.exists()) temp.length() else 0L
            val builder = Request.Builder().url(model.url)
            if (existing > 0) builder.header("Range", "bytes=$existing-")
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return Result.failure(IllegalStateException("HTTP ${response.code}"))
                }

                val resumed = existing > 0 && response.code == 206
                if (resumed && response.header("Content-Range")?.substringBefore('-') != "bytes $existing") {
                    return Result.failure(IllegalStateException("The server returned a different range. The download was saved."))
                }
                if (existing > 0 && !resumed) temp.delete()

                val total = if (resumed) {
                    response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: model.sizeBytes
                } else {
                    response.body?.contentLength()?.takeIf { it > 0 } ?: model.sizeBytes
                }

                val body = response.body
                    ?: return Result.failure(IllegalStateException("Empty response"))

                var downloaded = if (resumed) existing else 0L
                val out = FileOutputStream(temp, resumed)
                val buf = ByteArray(32 * 1024)
                var lastReport = 0L
                body.byteStream().use { input ->
                    out.use { output ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 250) {
                                val pct = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                                onProgress(DownloadProgress(downloaded, total, pct, "DOWNLOADING"))
                                lastReport = now
                            }
                        }
                    }
                }

                if (downloaded !in (model.sizeBytes - tolerance)..(model.sizeBytes + tolerance)) {
                    temp.delete()
                    return Result.failure(
                        IllegalStateException("Downloaded file invalid: $downloaded / ${model.sizeBytes} bytes")
                    )
                }

                Result.success(finalize(context, model, temp, target, onProgress))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            if (wakeLock?.isHeld == true) {
                runCatching { wakeLock.release() }
            }
        }
    }

    private fun finalize(
        context: Context,
        model: ModelInfo,
        temp: File,
        target: File,
        onProgress: (DownloadProgress) -> Unit,
    ): String {
        val installed = installFile(temp, target, File(context.filesDir, "models"))
        val bytes = installed.length()
        ModelCatalog.recordHash(context, model)
        onProgress(DownloadProgress(bytes, bytes, 100, "SUCCESSFUL"))
        return installed.absolutePath
    }

    internal fun installFile(temp: File, target: File, internal: File): File {
        val synced = runCatching { RandomAccessFile(temp, "rw").use { it.fd.sync() } }.isSuccess
        val bytes = temp.length()
        return if (synced && temp.renameTo(target)) target else {
            internal.apply { mkdirs() }
            val staged = File(internal, "${target.name}.installing")
            val final = File(internal, target.name)
            if (internal.usableSpace < bytes) {
                error("Download saved. NovaClaw needs %.1f GB of free phone storage to install it.".format(bytes / 1e9))
            }
            try {
                temp.inputStream().use { input ->
                    FileOutputStream(staged).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                check(staged.length() == bytes && staged.renameTo(final)) {
                    "Download saved, but Android could not install it in private storage."
                }
                temp.delete()
                final
            } finally {
                staged.delete()
            }
        }
    }

    fun cancel(context: Context, model: ModelInfo) {
        runCatching {
            File(ModelCatalog.modelsDir(context), "${model.fileName}.downloading").delete()
        }
    }
}
