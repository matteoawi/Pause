package it.matteo.pausa;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.os.Build;
import android.app.LocaleManager;
import java.util.Locale;

/** Per-app preferences; no device-wide locale or theme is changed. */
final class AppPreferences {
    static Context wrap(Context base) {
        Settings prefs = new Settings(base); Configuration config = new Configuration(base.getResources().getConfiguration());
        String language = prefs.text("language", "system");
        if (Build.VERSION.SDK_INT < 33 && (language.equals("it") || language.equals("en") || language.equals("es"))) {
            Locale locale = Locale.forLanguageTag(language); config.setLocales(new LocaleList(locale)); config.setLayoutDirection(locale);
        }
        String theme = prefs.text("appearance", "system");
        if (!theme.equals("system")) config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (theme.equals("dark") ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(config);
    }
    static Locale locale(Context context) { return context.getResources().getConfiguration().getLocales().get(0); }
    static String language(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList languages = context.getSystemService(LocaleManager.class).getApplicationLocales();
            return languages.isEmpty() ? "system" : languages.get(0).getLanguage();
        }
        return new Settings(context).text("language", "system");
    }
    private AppPreferences() { }
}
