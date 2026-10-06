package com.earthwheel.boost

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Limba aplicației: română (implicit) sau greacă; fără alegere manuală urmează limba telefonului. */
object LocaleHelper {
    private const val PREFS = "ewb_ui"
    private const val KEY = "lang"

    fun wrap(base: Context): Context {
        val lang = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
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
