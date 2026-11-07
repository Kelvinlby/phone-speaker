package com.kelvin.phonespeaker;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/** User choices that persist across launches. */
final class Prefs {
    private static final String FILE = "settings";
    static final String SPEAKER = "speaker";
    static final String MIC = "mic";
    static final String CALL_MODE = "call_mode";
    static final String TILE_ADDED = "tile_added";

    private Prefs() {}

    private static SharedPreferences of(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean get(Context context, String key, boolean fallback) {
        return of(context).getBoolean(key, fallback);
    }

    static void set(Context context, String key, boolean value) {
        of(context).edit().putBoolean(key, value).apply();
    }

    static boolean speaker(Context context) {
        return get(context, SPEAKER, true);
    }

    static boolean mic(Context context) {
        return get(context, MIC, true);
    }

    static boolean callMode(Context context) {
        return get(context, CALL_MODE, false);
    }

    static boolean hasMicPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** True if the mic should actually be used: the user wants it and Android allows it. */
    static boolean micUsable(Context context) {
        return mic(context) && hasMicPermission(context);
    }
}
