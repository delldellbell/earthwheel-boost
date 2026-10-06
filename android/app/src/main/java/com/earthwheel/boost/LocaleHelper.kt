package com.earthwheel.boost

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Limba aplicației: greacă (implicit) sau română, comutabilă din butonul din colțul de sus. */
object LocaleHelper {
    private const val PREFS = "ewb_ui"
    private const val KEY = "lang"
    private const val DEFAULT_LANG = "el"   // aplicația pornește în greacă

    fun wrap(base: Context): Context {
        val lang = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: DEFAULT_LANG
        val loc = Locale(lang)
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        return base.createConfigurationContext(cfg)
    }

    fun current(ctx: Context): String =
        if (ctx.resources.configuration.locales[0].language == "el") "el" else "ro"

    fun set(ctx: Context, lang: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).commit()
    }
}
