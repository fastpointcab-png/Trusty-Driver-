package com.trustyyellowcabs.driver.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Manages runtime and persistent bilingual localization (English / Tamil)
 * for the Driver App without modifying any backend or Firestore schema.
 */
object LocaleHelper {
    private const val PREFS_NAME = "TrustyYellowCabPrefs"
    private const val KEY_APP_LANGUAGE = "app_language"

    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_TAMIL = "ta"

    private val _currentLanguage = MutableStateFlow(LANGUAGE_ENGLISH)
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    fun init(context: Context) {
        val savedLang = getSavedLanguage(context)
        _currentLanguage.value = savedLang
        applyLocaleToResources(context, savedLang)
    }

    fun getSavedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_APP_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH
    }

    fun setLanguage(context: Context, languageCode: String, recreateActivity: Boolean = true) {
        val lang = if (languageCode == LANGUAGE_TAMIL) LANGUAGE_TAMIL else LANGUAGE_ENGLISH
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_APP_LANGUAGE, lang).apply()
        _currentLanguage.value = lang

        applyLocaleToResources(context, lang)

        if (recreateActivity && context is Activity) {
            context.recreate()
        }
    }

    fun applyLocaleToResources(context: Context, languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val res = context.resources
        val config = Configuration(res.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)

        // Also update applicationContext resources if different
        try {
            val appRes = context.applicationContext.resources
            if (appRes != res) {
                val appConfig = Configuration(appRes.configuration)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    appConfig.setLocales(LocaleList(locale))
                } else {
                    @Suppress("DEPRECATION")
                    appConfig.locale = locale
                }
                @Suppress("DEPRECATION")
                appRes.updateConfiguration(appConfig, appRes.displayMetrics)
            }
        } catch (ignored: Exception) {
        }
    }

    fun wrapContext(baseContext: Context): Context {
        val lang = getSavedLanguage(baseContext)
        val locale = Locale(lang)
        Locale.setDefault(locale)

        val config = Configuration(baseContext.resources.configuration)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
            baseContext.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            baseContext.createConfigurationContext(config)
        }
    }

    fun getLocalizedContext(context: Context, languageCode: String? = null): Context {
        val lang = languageCode ?: getSavedLanguage(context)
        val locale = Locale(lang)
        val config = Configuration(context.resources.configuration)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            context.createConfigurationContext(config)
        }
    }

    fun getString(context: Context, @StringRes resId: Int, vararg formatArgs: Any): String {
        return try {
            val localizedContext = getLocalizedContext(context)
            if (formatArgs.isNotEmpty()) {
                localizedContext.getString(resId, *formatArgs)
            } else {
                localizedContext.getString(resId)
            }
        } catch (e: Exception) {
            context.getString(resId)
        }
    }
}
