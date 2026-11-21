package ai.novaclaw.model

import android.content.Context

object CloudConfig {
    private const val PREFS = "nova_settings"
    private const val KEY_API = "cloud_api_key"
    private const val KEY_MODEL = "cloud_model"
    const val PREFERRED_MODEL = "gemini-3.8-flash"
    const val FALLBACK_MODEL = "gemini-2.5-flash"

    fun apiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, "").orEmpty().trim()

    fun model(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, PREFERRED_MODEL).orEmpty().ifBlank { PREFERRED_MODEL }

    fun setModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL, model).apply()
    }

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API, key.trim()).apply()
    }
}
