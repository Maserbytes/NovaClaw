package ai.novaclaw.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        context: Context,
        model: ModelInfo,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val dir = runCatching { ModelCatalog.modelsDir(context) }
            .getOrElse { return@withContext Result.failure(it) }
        val target = File(dir, model.fileName)
        val temp = File(dir, "${model.fileName}.downloading")

        if (ModelCatalog.isInstalled(context, model)) {
            return@withContext Result.success(target.absolutePath)
        }

        val available = ModelCatalog.freeSpaceBytes(context)
        if (available < model.sizeBytes * 0.95) {
            return@withContext Result.failure(
                IllegalStateException("Not enough storage: need %.1f GB, have %.1f GB".format(
                    model.sizeBytes / 1e9, available / 1e9))
            )
        }

        // Drop a partial file that is obviously oversized/corrupt.
        if (temp.exists() && temp.length() > model.sizeBytes + 8L * 1024 * 1024) temp.delete()

        try {
            val existing = if (temp.exists()) temp.length() else 0L
            val builder = Request.Builder().url(model.url)
            if (existing > 0) builder.header("Range", "bytes=$existing-")
            val response = client.newCall(builder.build()).execute()
            if (!response.isSuccessful && response.code != 206) {
                return@withContext Result.failure(IllegalStateException("HTTP ${response.code}"))
            }

            val resumed = existing > 0 && response.code == 206
            if (existing > 0 && !resumed) temp.delete()

            val total = if (resumed) {
                response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: model.sizeBytes
            } else {
                response.body?.contentLength()?.takeIf { it > 0 } ?: model.sizeBytes
            }

            val body = response.body
                ?: return@withContext Result.failure(IllegalStateException("Empty response"))

            var downloaded = if (resumed) existing else 0L
            val out = FileOutputStream(temp, resumed)
            val buf = ByteArray(32 * 1024)
            var lastReport = 0L
            body.byteStream().use { input ->
                out.use { output ->
                    while (true) {
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

            val tolerance = 8L * 1024 * 1024
            if (downloaded !in (model.sizeBytes - tolerance)..(model.sizeBytes + tolerance)) {
                temp.delete()
                return@withContext Result.failure(
                    IllegalStateException("Downloaded file invalid: $downloaded / ${model.sizeBytes} bytes")
                )
            }

            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                return@withContext Result.failure(IllegalStateException("Could not move model into place"))
            }

            onProgress(DownloadProgress(downloaded, total, 100, "SUCCESSFUL"))
            ModelCatalog.recordHash(context, model)
            // Force the OS to flush before LiteRT maps the file.
            runCatching {
                RandomAccessFile(target, "r").use { it.fd.sync() }
            }
            Result.success(target.absolutePath)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun cancel(context: Context, model: ModelInfo) {
        runCatching {
            File(ModelCatalog.modelsDir(context), "${model.fileName}.downloading").delete()
        }
    }
}
