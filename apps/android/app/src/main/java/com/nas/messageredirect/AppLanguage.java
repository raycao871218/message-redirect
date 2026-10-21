package com.nas.messageredirect;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;

/** Use Android's app-language setting on 13+, with a private preference on older devices. */
final class AppLanguage {
    static final String[] TAGS = {"", "zh-CN", "en", "ja"};
    private AppLanguage() {}
    static String selected(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList locales = context.getSystemService(LocaleManager.class).getApplicationLocales();
            return locales.isEmpty() ? "" : locales.get(0).toLanguageTag();
        }
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("language", "");
    }
    static int selectedIndex(Context context) {
        String tag = selected(context);
        if (tag.isEmpty()) return 0;
        String language = Locale.forLanguageTag(tag).getLanguage();
        for (int i = 1; i < TAGS.length; i++)
            if (Locale.forLanguageTag(TAGS[i]).getLanguage().equals(language)) return i;
        return 0;
    }
    static Context wrap(Context context) {
        if (Build.VERSION.SDK_INT >= 33) return context;
        String tag = selected(context);
        if (tag.isEmpty()) return context;
        Configuration config = new Configuration(context.getResources().getConfiguration());
        Locale locale = Locale.forLanguageTag(tag);
        if (Build.VERSION.SDK_INT >= 24) config.setLocales(new LocaleList(locale));
        else config.setLocale(locale);
        config.setLayoutDirection(locale);
        return context.createConfigurationContext(config);
    }
    static void select(Activity activity, String tag) {
        boolean valid = false;
        for (String supported : TAGS) if (supported.equals(tag)) valid = true;
        if (!valid) throw new IllegalArgumentException("Unsupported app language");
        if (selected(activity).equals(tag)) return;
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            activity.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("language", tag).apply();
            activity.recreate();
        }
    }
}
