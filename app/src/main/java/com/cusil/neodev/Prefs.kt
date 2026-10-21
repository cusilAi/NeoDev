package com.cusil.neodev

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("neodev_prefs", Context.MODE_PRIVATE)

    var githubToken: String
        get() = sp.getString("gh_token", "") ?: ""
        set(v) { sp.edit().putString("gh_token", v.trim()).apply() }

    var githubLogin: String
        get() = sp.getString("gh_login", "") ?: ""
        set(v) { sp.edit().putString("gh_login", v.trim()).apply() }

    var geminiKey: String
        get() = sp.getString("gemini_key", "") ?: ""
        set(v) { sp.edit().putString("gemini_key", v.trim()).apply() }

    var model: String
        get() = (sp.getString("model", "") ?: "").ifBlank { Config.DEFAULT_MODEL }
        set(v) { sp.edit().putString("model", v.trim()).apply() }

    var authorName: String
        get() = sp.getString("author_name", "") ?: ""
        set(v) { sp.edit().putString("author_name", v.trim()).apply() }

    var authorEmail: String
        get() = sp.getString("author_email", "") ?: ""
        set(v) { sp.edit().putString("author_email", v.trim()).apply() }

    var fontSize: Int
        get() = sp.getInt("font_size", 14)
        set(v) { sp.edit().putInt("font_size", v).apply() }

    fun hasBuiltInKey(): Boolean = Config.keyLooksValid(Config.GEMINI_API_KEY)

    fun effectiveGeminiKey(): String {
        val own = geminiKey
        if (own.isNotBlank()) return own
        return if (hasBuiltInKey()) Config.GEMINI_API_KEY else ""
    }
}
