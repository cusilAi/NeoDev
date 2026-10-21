package com.cusil.neodev

object Config {
    // Built-in Gemini key. The placeholder text below is replaced by the setup command in the README.
    // Users can always override it with their own key in Settings.
    const val GEMINI_API_KEY = "Your API Key"

    const val DEFAULT_MODEL = "gemini-3.5-flash"
    const val FALLBACK_MODEL = "gemini-2.5-flash"

    fun keyLooksValid(k: String): Boolean = k.length >= 20 && !k.contains(' ')
}
