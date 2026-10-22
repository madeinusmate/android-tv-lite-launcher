package dev.litehome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public final class LauncherCover extends AccessibilityService {
    static final String EXTRA_COVER = "dev.litehome.cover";

    static final int READY = 0;
    static final int ENABLE_COVER = 2;
    static final int SWITCH_HOME = 3;

    private static final String LAUNCHERX = "com.google.android.apps.tv.launcherx";
    private static final String TV_LAUNCHER = "com.google.android.tvlauncher";
    private static final String SETUP_WRAITH = "com.google.android.tungsten.setupwraith";
    private static final int ABSENT = Integer.MIN_VALUE;
    private static final long QUIET_MS = 80;
    private static final long MASK_MS = 1200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable dropMask = this::hideMask;
    private long lastLaunch;
    private View mask;
    private ContentObserver keyboardWatch;

    static int step(Context context) {
        if (!googleTv(context)) {
            return READY;
        }
        if (!enabled(context)) {
            return ENABLE_COVER;
        }
        if (weAreHome(context)) {
            return SWITCH_HOME;
        }
        return READY;
    }

    static String stepLabel(int step) {
        if (step == SWITCH_HOME) {
            return "Set Google TV as Home";
        }
        return "Turn on Home cover";
    }

    static Intent stepIntent(Context context) {
        int step = step(context);
        if (step == SWITCH_HOME) {
            return new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if (step == ENABLE_COVER) {
            return accessibilitySettings(context);
        }
        return null;
    }

    static boolean enabled(Context context) {
        String setting = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (setting == null || setting.isEmpty()) {
            return false;
        }
        ComponentName service = new ComponentName(context, LauncherCover.class);
        TextUtils.SimpleStringSplitter services = new TextUtils.SimpleStringSplitter(':');
        services.setString(setting);
        while (services.hasNext()) {
            ComponentName enabled = ComponentName.unflattenFromString(services.next());
            if (service.equals(enabled)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null) {
            return;
        }
        if (getPackageName().contentEquals(pkg)) {
            handler.post(this::hideMask);
            return;
        }
        if (!covers(pkg.toString(), event.getClassName())) {
            return;
        }
        showMask();
        if (oursOnTop()) {
            hideMask();
            return;
        }
        bringForward();
    }

    @Override
    protected void onServiceConnected() {
        if (keyboardWatch == null) {
            keyboardWatch = ScreenKeyboard.watch(this);
        } else {
            ScreenKeyboard.pin(this);
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        CharSequence pkg = root.getPackageName();
        CharSequence cls = root.getClassName();
        root.recycle();
        if (pkg != null && covers(pkg.toString(), cls) && !oursOnTop()) {
            showMask();
            bringForward();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (keyboardWatch != null) {
            getContentResolver().unregisterContentObserver(keyboardWatch);
            keyboardWatch = null;
        }
        hideMask();
        super.onDestroy();
    }

    private void bringForward() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < QUIET_MS) {
            return;
        }
        lastLaunch = now;
        Intent home = new Intent(this, HomeActivity.class)
                .putExtra(EXTRA_COVER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(home);
        } catch (RuntimeException ignored) {
            lastLaunch = 0;
        }
    }

    private boolean oursOnTop() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        CharSequence pkg = root.getPackageName();
        root.recycle();
        return pkg != null && getPackageName().contentEquals(pkg);
    }

    private void showMask() {
        if (mask != null) {
            handler.removeCallbacks(dropMask);
            handler.postDelayed(dropMask, MASK_MS);
            return;
        }
        View view = new View(this);
        view.setBackgroundColor(0xFF1C1C1E);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.START;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
        }
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, params);
            mask = view;
            handler.postDelayed(dropMask, MASK_MS);
        } catch (RuntimeException ignored) {
        }
    }

    private void hideMask() {
        handler.removeCallbacks(dropMask);
        if (mask == null) {
            return;
        }
        View view = mask;
        mask = null;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(view);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean covers(String pkg, CharSequence className) {
        if (getPackageName().equals(pkg) || "android".equals(pkg) || "com.android.systemui".equals(pkg)) {
            return false;
        }
        if (SETUP_WRAITH.equals(pkg)) {
            return className != null && className.toString().contains("RecoveryActivity");
        }
        if (LAUNCHERX.equals(pkg) || TV_LAUNCHER.equals(pkg)) {
            return className != null && homeActivity(className.toString());
        }
        String home = defaultHomePackage(this);
        return pkg.equals(home);
    }

    private static boolean homeActivity(String className) {
        return className.endsWith(".home.HomeActivity")
                || className.endsWith(".home.VanillaModeHomeActivity")
                || className.endsWith(".MainActivity");
    }

    private static boolean googleTv(Context context) {
        PackageManager pm = context.getPackageManager();
        return packageState(pm, LAUNCHERX) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || packageState(pm, LAUNCHERX) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || packageState(pm, TV_LAUNCHER) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || packageState(pm, TV_LAUNCHER) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }

    private static int packageState(PackageManager pm, String pkg) {
        try {
            pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return ABSENT;
        }
        return pm.getApplicationEnabledSetting(pkg);
    }

    private static boolean weAreHome(Context context) {
        return context.getPackageName().equals(defaultHomePackage(context));
    }

    private static String defaultHomePackage(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = context.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (info == null || info.activityInfo == null) {
            return null;
        }
        return info.activityInfo.packageName;
    }

    private static Intent accessibilitySettings(Context context) {
        ComponentName service = new ComponentName(context, LauncherCover.class);
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra("android.intent.extra.COMPONENT_NAME", service.flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (context.getPackageManager().resolveActivity(details, 0) != null) {
            return details;
        }
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
