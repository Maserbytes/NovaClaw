package ai.novaclaw.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class ModelInfo(
    val id: String,
    val name: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val minRamGb: Int,
    val kind: Kind = Kind.LITE_RT,
    val vision: Boolean = false,
) {
    enum class Kind { LITE_RT, CLOUD }
}

object ModelCatalog {

    private const val PREFS = "nova_models"

    val GEMMA_E2B = ModelInfo(
        id = "gemma4-e2b",
        name = "Gemma 4 E2B (on-device)",
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        minRamGb = 8,
        vision = true,
    )

    val GEMMA_E4B = ModelInfo(
        id = "gemma4-e4b",
        name = "Gemma 4 E4B (on-device)",
        fileName = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240L,
        minRamGb = 10,
        vision = true,
    )

    val QWEN_35_4B = ModelInfo(
        id = "qwen3.5-4b",
        name = "Qwen 3.5 4B (on-device)",
        fileName = "Qwen3.5-4B_mixed_int4.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3.5-4B/resolve/main/Qwen3.5-4B_mixed_int4.litertlm",
        sizeBytes = 2_754_166_784L,
        minRamGb = 8,
    )

    val QWEN_35_2B = ModelInfo(
        id = "qwen3.5-2b",
        name = "Qwen 3.5 2B (on-device)",
        fileName = "Qwen3.5-2B_int8.litertlm",
        url = "https://huggingface.co/litert-community/Qwen3.5-2B/resolve/main/Qwen3.5-2B_int8.litertlm",
        sizeBytes = 2_119_843_840L,
        minRamGb = 8,
    )

    fun hasLegacyQwenDownload(context: Context): Boolean =
        listOf(modelsDir(context), File(context.filesDir, "models")).any { dir ->
            File(dir, QWEN_35_4B.fileName).isFile || File(dir, "${QWEN_35_4B.fileName}.downloading").isFile
        }

    val GEMINI = ModelInfo(
        id = "gemini-flash",
        name = "Gemini Flash (cloud)",
        fileName = "",
        url = "",
        sizeBytes = 0L,
        minRamGb = 0,
        kind = ModelInfo.Kind.CLOUD,
        vision = true,
    )

    val LOCAL = listOf(GEMMA_E2B, GEMMA_E4B, QWEN_35_2B)

    fun byId(id: String): ModelInfo? = LOCAL.firstOrNull {
        it.id.equals(id, ignoreCase = true) ||
            it.fileName.equals(id, ignoreCase = true) ||
            it.id.replace("-", "").equals(id.replace("-", ""), ignoreCase = true)
    } ?: if (id.equals(GEMINI.id, ignoreCase = true)) GEMINI else null

    fun recommended(context: Context): ModelInfo {
        val ram = BackendSelector.ram(context)
        val preferred = if (ram.totalGb >= 12 && ram.availableGb >= 6) GEMMA_E4B else GEMMA_E2B
        return if (isInstalled(context, preferred)) preferred
        else LOCAL.firstOrNull { isInstalled(context, it) } ?: preferred
    }

    /** External storage first, writable-probe enforced, internal fallback. */
    fun modelsDir(context: Context): File {
        val external = context.getExternalFilesDir(null)?.let { File(it, "models") }
        if (external != null && prepare(external)) return external
        val internal = File(context.filesDir, "models")
        if (prepare(internal)) return internal
        error("No writable model directory available")
    }

    private fun prepare(dir: File): Boolean {
        if (!(dir.isDirectory || dir.mkdirs() || dir.isDirectory)) return false
        return runCatching {
            val probe = File(dir, ".nova-write-probe")
            FileOutputStream(probe, false).use { it.write(1) }
            probe.delete()
            true
        }.getOrDefault(false)
    }

    fun path(context: Context, model: ModelInfo): String {
        val preferred = File(modelsDir(context), model.fileName)
        if (preferred.isFile) return preferred.absolutePath
        // Keep models downloaded during an internal-storage fallback available
        // when Android later remounts external storage.
        val internal = File(context.filesDir, "models/${model.fileName}")
        return if (internal.isFile) internal.absolutePath else preferred.absolutePath
    }

    fun isInstalled(context: Context, model: ModelInfo): Boolean {
        val file = File(path(context, model))
        if (!file.exists()) return false
        // Lenient: any real .litertlm is at least 100 MB. Exact size/hash
        // differences are logged at load time but must never block a usable model.
        return file.length() > 100L * 1024 * 1024
    }

    /** Advisory integrity check. A mismatch is logged, not used to block loading. */
    fun verifyIntegrity(context: Context, model: ModelInfo): Boolean {
        val recorded = recordedHash(context, model) ?: return true
        val file = File(path(context, model))
        if (!file.exists()) return false
        val matches = runCatching { sha256(file) == recorded }.getOrDefault(true)
        if (!matches) {
            ai.novaclaw.util.NovaLog.w("ModelCatalog",
                "Model hash differs from recorded value for ${model.id}; attempting load anyway")
        }
        return true
    }

    fun recordedHash(context: Context, model: ModelInfo): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("hash_${model.id}", null)

    fun recordHash(context: Context, model: ModelInfo) {
        val hash = runCatching { sha256(File(path(context, model))) }.getOrNull() ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("hash_${model.id}", hash).apply()
    }

    fun delete(context: Context, model: ModelInfo): Boolean {
        val locations = listOf(modelsDir(context), File(context.filesDir, "models")).distinct()
        val files = locations.flatMap { dir ->
            listOf(File(dir, model.fileName), File(dir, "${model.fileName}.downloading"))
        }
        val removed = files.map { !it.exists() || it.delete() }.all { it }
        if (removed) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove("hash_${model.id}").apply()
        }
        return removed
    }

    fun freeSpaceBytes(context: Context): Long =
        runCatching { android.os.StatFs(modelsDir(context).path).availableBytes }.getOrDefault(0L)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
