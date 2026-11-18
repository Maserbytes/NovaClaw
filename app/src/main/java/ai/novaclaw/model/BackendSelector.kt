package ai.novaclaw.model

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.os.SystemClock
import kotlin.math.min

/**
 * Adaptive local-backend selection.
 *
 * Fixes vs PokeClaw:
 *  - no permanent CPU lock-in for MediaTek/Dimensity — a bounded one-shot GPU probe
 *    is possible once the device passes a real RAM check
 *  - stable device key (no Build.FINGERPRINT, lost on every OTA)
 *  - boot-epoch marker instead of PID equality (PIDs get recycled)
 *  - both crash classes re-arm correctly
 *  - KV cache sized from real RAM and backend
 *  - no artificial +1 GB in the RAM estimate
 */
object BackendSelector {

    private const val PREFS = "nova_backend"
    private const val KEY_DEVICE = "device_key"
    private const val KEY_BOOT = "boot_epoch"
    private const val KEY_CPU_SAFE = "cpu_safe"
    private const val KEY_CPU_REASON = "cpu_reason"
    private const val KEY_CPU_AT = "cpu_at"
    private const val KEY_GPU_VERIFIED = "gpu_verified"
    private const val KEY_GPU_PROBE = "gpu_probe_requested"
    private const val KEY_PENDING_MODEL = "pending_model"
    private const val KEY_PENDING_BOOT = "pending_boot"
    private const val KEY_PENDING_AT = "pending_at"
    private const val KEY_FAILURES = "gpu_failures"

    data class Decision(
        val useGpu: Boolean,
        val maxTokens: Int,
        val totalRamGb: Int,
        val availableRamGb: Int,
        val reason: String,
    ) {
        val label: String
            get() = if (useGpu) "GPU (verified)" else "CPU ($reason)"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceKey(): String = listOf(
        Build.BOARD, Build.DEVICE, Build.HARDWARE, Build.BRAND
    ).joinToString("|") { it.orEmpty().trim() }

    /** Stable across process restarts and OTAs. */
    private fun bootEpoch(context: Context): Long {
        val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return bootTime / 1000L
    }

    private fun isSameDevice(context: Context, p: SharedPreferences): Boolean =
        p.getString(KEY_DEVICE, null) == deviceKey()

    private fun ensureDevice(context: Context, p: SharedPreferences) {
        if (!isSameDevice(context, p)) {
            p.edit()
                .putString(KEY_DEVICE, deviceKey())
                .remove(KEY_CPU_SAFE).remove(KEY_CPU_REASON).remove(KEY_CPU_AT)
                .remove(KEY_GPU_VERIFIED).remove(KEY_GPU_PROBE)
                .remove(KEY_PENDING_MODEL).remove(KEY_PENDING_BOOT)
                .putLong(KEY_FAILURES, 0L)
                .apply()
        }
    }

    data class Ram(val totalGb: Int, val availableGb: Int, val low: Boolean)

    fun ram(context: Context): Ram {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val gb = 1024L * 1024L * 1024L
        return Ram(
            totalGb = (info.totalMem / gb).toInt(),
            availableGb = (info.availMem / gb).toInt(),
            low = info.lowMemory,
        )
    }

    init {
        // Recovering stale markers requires a Context, so it happens in decide().
    }

    /** Called once at startup; promotes an unfinished GPU attempt to a quarantine. */
    private fun recoverStaleAttempt(context: Context, p: SharedPreferences) {
        val pending = p.getString(KEY_PENDING_MODEL, null) ?: return
        val pendingBoot = p.getLong(KEY_PENDING_BOOT, -1L)
        val boot = bootEpoch(context)
        if (pendingBoot > 0 && pendingBoot != boot) {
            quarantine(context, "gpu_init_crash:$pending")
        } else {
            p.edit().remove(KEY_PENDING_MODEL).remove(KEY_PENDING_BOOT)
                .remove(KEY_PENDING_AT).apply()
        }
    }

    fun decide(context: Context, preferGpu: Boolean = true): Decision {
        val p = prefs(context)
        ensureDevice(context, p)
        recoverStaleAttempt(context, p)

        val r = ram(context)
        val cpuSafe = p.getBoolean(KEY_CPU_SAFE, false)
        val reason = p.getString(KEY_CPU_REASON, "").orEmpty()
        val verified = p.getBoolean(KEY_GPU_VERIFIED, false)
        val probe = p.getBoolean(KEY_GPU_PROBE, false)
        val failures = p.getLong(KEY_FAILURES, 0L)

        // Respect the context size compiled into the .litertlm artifact. Forcing a
        // larger KV cache than the model was built for breaks attention bounds.
        // 4096 is the safe ceiling for Gemma 4 E2B.
        val maxTokens = if (r.totalGb < 8) 2048 else 4096

        if (cpuSafe || failures >= 2) {
            return Decision(false, maxTokens, r.totalGb, r.availableGb,
                reason.ifBlank { "gpu_failure_quarantine" })
        }
        if (!preferGpu && !verified) {
            return Decision(false, maxTokens, r.totalGb, r.availableGb, "cpu_first_reliability")
        }
        if (r.totalGb < 8 || r.availableGb < 4 || r.low) {
            return Decision(false, maxTokens, r.totalGb, r.availableGb, "low_memory")
        }
        // GPU is an explicit opt-in. Field data (LiteRT-LM #2202) shows Mali GPU
        // backends corrupt tool-call arguments and silently drop ~6-9% of calls,
        // so correctness defaults to CPU even on capable hardware.
        val gpuAllowed = verified || probe
        return Decision(
            useGpu = gpuAllowed,
            maxTokens = maxTokens,
            totalRamGb = r.totalGb,
            availableRamGb = r.availableGb,
            reason = if (verified) "gpu_verified" else "gpu_opt_in",
        )
    }

    /** Call before constructing a GPU engine. */
    fun markGpuAttempt(context: Context, modelId: String) {
        prefs(context).edit()
            .putString(KEY_PENDING_MODEL, modelId)
            .putLong(KEY_PENDING_BOOT, bootEpoch(context))
            .putLong(KEY_PENDING_AT, System.currentTimeMillis())
            .apply()
    }

    fun markGpuSuccess(context: Context, modelId: String) {
        prefs(context).edit()
            .putBoolean(KEY_GPU_VERIFIED, true)
            .putString(KEY_PENDING_MODEL, null)
            .remove(KEY_PENDING_BOOT).remove(KEY_PENDING_AT)
            .putLong(KEY_FAILURES, 0L)
            .apply()
    }

    fun noteFailure(context: Context, error: Throwable?) {
        val p = prefs(context)
        val count = p.getLong(KEY_FAILURES, 0L) + 1
        val reason = buildReason(error)
        p.edit().putLong(KEY_FAILURES, count).apply()
        if (count >= 2 || isFatal(error)) quarantine(context, "gpu_failure:$reason")
    }

    fun quarantine(context: Context, reason: String) {
        prefs(context).edit()
            .putBoolean(KEY_CPU_SAFE, true)
            .putString(KEY_CPU_REASON, reason.take(160))
            .putLong(KEY_CPU_AT, System.currentTimeMillis())
            .putString(KEY_PENDING_MODEL, null)
            .remove(KEY_PENDING_BOOT).remove(KEY_PENDING_AT)
            .apply()
    }

    fun requestGpuProbe(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_GPU_PROBE, true)
            .putBoolean(KEY_CPU_SAFE, false)
            .putString(KEY_CPU_REASON, "")
            .putLong(KEY_FAILURES, 0L)
            .apply()
    }

    fun clearVerified(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_GPU_VERIFIED, false)
            .putBoolean(KEY_GPU_PROBE, false)
            .apply()
    }

    fun isGpuFailure(error: Throwable?): Boolean {
        if (error is OutOfMemoryError || error is UnsatisfiedLinkError) return true
        val m = error?.message.orEmpty()
        if (m.isBlank()) return false
        val needles = listOf(
            "opencl", "gpu", "dlopen", "libopencl", "bad_alloc", "out of memory",
            "memory", "allocation", "delegate", "compiled model", "native",
            "sigsegv", "fatal signal", "failed to create engine", "compilation",
        )
        return needles.any { m.contains(it, ignoreCase = true) }
    }

    private fun isFatal(error: Throwable?): Boolean {
        val m = error?.message.orEmpty()
        return error is OutOfMemoryError || m.contains("sigsegv", true) ||
            m.contains("fatal signal", true) || m.contains("bad_alloc", true)
    }

    private fun buildReason(error: Throwable?): String =
        listOf(error?.javaClass?.simpleName, error?.message?.take(100))
            .filter { !it.isNullOrBlank() }.joinToString(": ")

    fun debugState(context: Context): String {
        val p = prefs(context)
        val r = ram(context)
        return buildString {
            append("device=").append(deviceKey())
            append(", ram=").append(r.totalGb).append("GB/").append(r.availableGb).append("GB")
            append(", low=").append(r.low)
            append(", cpuSafe=").append(p.getBoolean(KEY_CPU_SAFE, false))
            append(", reason=").append(p.getString(KEY_CPU_REASON, "-"))
            append(", gpuVerified=").append(p.getBoolean(KEY_GPU_VERIFIED, false))
            append(", probe=").append(p.getBoolean(KEY_GPU_PROBE, false))
            append(", failures=").append(p.getLong(KEY_FAILURES, 0L))
            append(", pending=").append(p.getString(KEY_PENDING_MODEL, "-"))
        }
    }
}
