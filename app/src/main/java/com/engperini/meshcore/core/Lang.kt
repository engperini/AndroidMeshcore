package com.engperini.meshcore.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** App language: English or Portuguese (Brazil). Follows the system unless chosen in the app (as in MeshSDR). */
object Lang {
    /** Compose state, so screens redraw when the language is switched. */
    var portuguese: Boolean by mutableStateOf(Locale.getDefault().language == "pt")

    /** Applies a saved choice: "pt", "en", or null for the system language. */
    fun apply(code: String?) {
        portuguese = when (code) {
            "pt" -> true
            "en" -> false
            else -> Locale.getDefault().language == "pt"
        }
    }
}

/** The text in the current app language. */
fun tr(en: String, pt: String): String = if (Lang.portuguese) pt else en
