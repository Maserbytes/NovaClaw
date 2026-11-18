package ai.novaclaw.util

import android.util.Log

/** Traceable logging: one tag, structured messages, no silent failures. */
object NovaLog {
    private const val PREFIX = "NovaClaw/"

    fun d(tag: String, msg: String) = Log.d(PREFIX + tag, msg)
    fun i(tag: String, msg: String) = Log.i(PREFIX + tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) =
        if (t != null) Log.w(PREFIX + tag, msg, t) else Log.w(PREFIX + tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        if (t != null) Log.e(PREFIX + tag, msg, t) else Log.e(PREFIX + tag, msg)

    /** Log a decision branch with its reason — logcat alone must explain the run. */
    fun decision(tag: String, name: String, choice: String, reason: String) =
        i(tag, "decision:$name=$choice reason=$reason")
}
