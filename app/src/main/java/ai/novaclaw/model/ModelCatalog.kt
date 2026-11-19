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
    )

    val GEMMA_E4B = ModelInfo(
        id = "gemma4-e4b",
        name = "Gemma 4 E4B (on-device)",
        fileName = "gemma-4-E4B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        sizeBytes = 3_659_530_240L,
        minRamGb = 10,
    )

    val GEMINI = ModelInfo(
        id = "gemini-flash",
        name = "Gemini Flash (cloud)",
        fileName = "",
        url = "",
        sizeBytes = 0L,
        minRamGb = 0,
        kind = ModelInfo.Kind.CLOUD,
    )

    val LOCAL = listOf(GEMMA_E2B, GEMMA_E4B)

    fun byId(id: String): ModelInfo? = LOCAL.firstOrNull {
        it.id.equals(id, ignoreCase = true) ||
            it.fileName.equals(id, ignoreCase = true) ||
            it.id.replace("-", "").equals(id.replace("-", ""), ignoreCase = true)
    } ?: if (id.equals(GEMINI.id, ignoreCase = true)) GEMINI else null

    fun recommended(context: Context): ModelInfo {
        val ram = BackendSelector.ram(context)
        return if (ram.totalGb >= 12 && ram.availableGb >= 6) GEMMA_E4B else GEMMA_E2B
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

    fun path(context: Context, model: ModelInfo): String =
        File(modelsDir(context), model.fileName).absolutePath

    fun isInstalled(context: Context, model: ModelInfo): Boolean {
        val file = File(path(context, model))
        if (!file.exists()) return false
        // Tight bound: a download truncated by more than ~8 MB is corrupt.
        val tolerance = 8L * 1024 * 1024
        // Cheap size check for UI status. Full SHA-256 verification is done once
        // when the engine opens the model (see verifyIntegrity).
        return file.length() in (model.sizeBytes - tolerance)..(model.sizeBytes + tolerance)
    }

    /** Full integrity check, called once before LiteRT maps the weights. */
    fun verifyIntegrity(context: Context, model: ModelInfo): Boolean {
        val recorded = recordedHash(context, model) ?: return true
        val file = File(path(context, model))
        if (!file.exists()) return false
        return runCatching { sha256(file) == recorded }.getOrDefault(false)
    }

    fun recordedHash(context: Context, model: ModelInfo): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("hash_${model.id}", null)

    fun recordHash(context: Context, model: ModelInfo) {
        val hash = runCatching { sha256(File(path(context, model))) }.getOrNull() ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("hash_${model.id}", hash).apply()
    }

    fun delete(context: Context, model: ModelInfo): Boolean {
        val dir = modelsDir(context)
        val file = File(dir, model.fileName)
        File(dir, "${model.fileName}.downloading").delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("hash_${model.id}").apply()
        return !file.exists() || file.delete()
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
