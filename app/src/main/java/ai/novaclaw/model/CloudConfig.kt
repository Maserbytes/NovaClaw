package ai.novaclaw.model

import android.content.Context

object CloudConfig {
    private const val PREFS = "nova_settings"
    private const val KEY_API = "cloud_api_key"
    const val MODEL = "gemini-2.5-flash"

    fun apiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, "").orEmpty().trim()

    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API, key.trim()).apply()
    }
}
