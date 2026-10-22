package dev.litehome;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ScreenKeyboard {
    private static final String GBOARD =
            "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME";
    private static final String REMOTE = "com.google.android.tv.remote.service";
    private static final String SHOW_WITH_HARD_KEYBOARD = "show_ime_with_hard_keyboard";

    private static boolean applying;

    private ScreenKeyboard() {
    }

    static ContentObserver watch(Context context) {
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                pin(context);
            }
        };
        ContentResolver resolver = context.getContentResolver();
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer);
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, observer);
        pin(context);
        return observer;
    }

    static void pin(Context context) {
        if (applying) {
            return;
        }
        applying = true;
        try {
            apply(context);
        } catch (SecurityException ex) {
            Log.w("LiteHome", "can't pin the on-screen keyboard", ex);
        } finally {
            applying = false;
        }
    }

    private static void apply(Context context) {
        InputMethodManager imm =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) {
            return;
        }
        List<InputMethodInfo> installed = imm.getInputMethodList();
        if (installed == null || installed.isEmpty()) {
            return;
        }
        InputMethodInfo screen = null;
        for (InputMethodInfo info : installed) {
            if (screenScore(info) > screenScore(screen)) {
                screen = info;
            }
        }
        if (screen == null) {
            return;
        }
        List<InputMethodInfo> enabled = imm.getEnabledInputMethodList();
        if (enabled == null) {
            enabled = new ArrayList<>();
        }
        String raw = describe(imm, enabled, false, null);
        String cleaned = describe(imm, enabled, true, screen.getId());
        InputMethodInfo current = currentIme(imm);
        String want = screenScore(current) > 0 ? current.getId() : screen.getId();
        ContentResolver resolver = context.getContentResolver();
        if (!cleaned.equals(raw)) {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_INPUT_METHODS, cleaned);
        }
        if (screenScore(current) <= 0) {
            Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, want);
            Log.i("LiteHome", "keyboard " + (current == null ? "none" : current.getId()) + " -> " + want);
        }
        showWithHardwareKeyboard(resolver);
    }

    private static void showWithHardwareKeyboard(ContentResolver resolver) {
        try {
            if (Settings.Secure.getInt(resolver, SHOW_WITH_HARD_KEYBOARD, 0) == 1) {
                return;
            }
        } catch (SecurityException ignored) {
        }
        Settings.Secure.putInt(resolver, SHOW_WITH_HARD_KEYBOARD, 1);
    }

    private static String describe(
            InputMethodManager imm, List<InputMethodInfo> infos, boolean dropRemote, String ensureId) {
        List<String> kept = new ArrayList<>();
        boolean hasEnsure = false;
        for (InputMethodInfo info : infos) {
            if (dropRemote && REMOTE.equals(info.getPackageName())) {
                continue;
            }
            kept.add(entry(imm, info));
            if (ensureId != null && ensureId.equals(info.getId())) {
                hasEnsure = true;
            }
        }
        if (ensureId != null && !hasEnsure) {
            kept.add(0, ensureId);
        }
        return String.join(":", kept);
    }

    private static String entry(InputMethodManager imm, InputMethodInfo info) {
        StringBuilder entry = new StringBuilder(info.getId());
        List<InputMethodSubtype> subtypes = imm.getEnabledInputMethodSubtypeList(info, true);
        if (subtypes != null) {
            for (InputMethodSubtype subtype : subtypes) {
                entry.append(';').append(subtype.hashCode());
            }
        }
        return entry.toString();
    }

    private static InputMethodInfo currentIme(InputMethodManager imm) {
        try {
            Object value = InputMethodManager.class.getMethod("getCurrentInputMethodInfo").invoke(imm);
            if (value instanceof InputMethodInfo info) {
                return info;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static int screenScore(InputMethodInfo info) {
        if (info == null || REMOTE.equals(info.getPackageName()) || voice(info)) {
            return -1;
        }
        if (GBOARD.equals(info.getId())) {
            return 100;
        }
        if ("com.google.android.inputmethod.latin".equals(info.getPackageName())) {
            return 80;
        }
        if (info.getId().toLowerCase(Locale.US).contains("leanback")) {
            return 60;
        }
        return 10;
    }

    private static boolean voice(InputMethodInfo info) {
        String service = info.getServiceName().toLowerCase(Locale.US);
        String pkg = info.getPackageName();
        return service.contains("voice")
                || "com.google.android.katniss".equals(pkg)
                || "com.google.android.tts".equals(pkg);
    }
}
