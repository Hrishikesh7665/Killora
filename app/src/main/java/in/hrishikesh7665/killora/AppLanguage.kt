package `in`.hrishikesh7665.killora

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The in-app language choice, built on Android's per-app language support (AppCompat applies and
 * stores it on every Android version this app supports, and on Android 13+ it also shows up under
 * system Settings › Apps › Killora › Language).
 *
 * Every user-facing string lives in `res/values/strings.xml`. To add a language:
 *  1. add `res/values-<code>/strings.xml` (copy `values/strings.xml` and translate it),
 *  2. add the code to [supportedTags] below,
 *  3. add the same code to `res/xml/locales_config.xml`.
 *
 * Only English ships today, so choosing a language changes nothing visible yet.
 */
object AppLanguage {

    /** BCP-47 tags of the languages the app has translations for. Keep in sync with
     *  `res/xml/locales_config.xml` and the `values-*` folders. */
    val supportedTags: List<String> = listOf("en")

    /** The language the user picked, or null while the app follows the system language. */
    fun selectedTag(): String? {
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.isEmpty) return null
        val language = current.get(0)?.language ?: return null
        return supportedTags.firstOrNull { Locale.forLanguageTag(it).language == language }
    }

    /** Applies [tag], or goes back to following the system language when it is null. Open
     *  screens recreate themselves so the change shows immediately. */
    fun select(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
        )
    }

    /** The language's own name for itself ("English", "Deutsch", ...), the usual way to list
     *  languages so people can find theirs whatever language the app is currently in. */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
