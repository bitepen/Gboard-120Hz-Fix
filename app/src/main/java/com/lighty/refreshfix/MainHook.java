package com.lighty.refreshfix;

import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.google.android.inputmethod.latin";
    private static final float BOOST_FPS = 120.0f;
    private static final float IDLE_FPS = 0.0f;
    // 停手 2.5 秒后解除窗口 120Hz 下限锁，交还给 LTPO 降频至 24Hz/1Hz
    private static final long IDLE_TIMEOUT_MS = 2500L;
    // 触摸/打字事件节流间隔（避免高频移动时反复操作 Handler）
    private static final long INTERACTION_THROTTLE_MS = 250L;

    private static final int CATEGORY_LOW = 2;        // 30Hz
    private static final int CATEGORY_NORMAL = 3;     // 60Hz
    private static final int CATEGORY_HIGH_HINT = 4;
    private static final int CATEGORY_HIGH = 5;       // 120Hz

    private Handler mainHandler;
    private Object currentIms;
    private Window currentWindow;
    private volatile boolean isLocked120 = false;
    private volatile long lastScheduleTime = 0L;

    private final Runnable idleReleaseTask = new Runnable() {
        @Override
        public void run() {
            isLocked120 = false;
            setWindowMinRefreshRate(getActiveWindow(), IDLE_FPS);
        }
    };

    private Handler getMainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    private Window getActiveWindow() {
        if (currentWindow != null) {
            return currentWindow;
        }
        if (currentIms != null) {
            try {
                Dialog dialog = (Dialog) XposedHelpers.callMethod(currentIms, "getWindow");
                if (dialog != null) {
                    currentWindow = dialog.getWindow();
                }
            } catch (Throwable ignored) {
            }
        }
        return currentWindow;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // 1. 关闭 Android 15/16+ 针对输入法的打字限帧标志
        disableToolkitTypingFrameRateLimit(lpparam.classLoader);

        // 2. 全程拦截 Gboard 渲染时的 60Hz 投票（不设状态门控，仅放行 0/1 空闲投票）
        hookFrameRateVotes(lpparam.classLoader);

        // 3. 监听窗口弹出（复刻四月份验证有效的窗口属性修改点）
        XposedHelpers.findAndHookMethod(
                "android.inputmethodservice.InputMethodService",
                lpparam.classLoader,
                "showWindow",
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        currentIms = param.thisObject;
                        Dialog dialog = (Dialog) XposedHelpers.callMethod(currentIms, "getWindow");
                        if (dialog != null && dialog.getWindow() != null) {
                            currentWindow = dialog.getWindow();
                            boostAndResetTimer(true);
                        }
                    }
                }
        );

        // 4. 监听窗口隐藏：立即释放 120Hz 锁
        XposedHelpers.findAndHookMethod(
                "android.inputmethodservice.InputMethodService",
                lpparam.classLoader,
                "hideWindow",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        isLocked120 = false;
                        getMainHandler().removeCallbacks(idleReleaseTask);
                        setWindowMinRefreshRate(getActiveWindow(), IDLE_FPS);
                    }
                }
        );

        // 5. 核心触摸入口 A：Hook JNI 回调入口 InputEventReceiver.dispatchInputEvent（C++ 直接调用，绝不会被内联）
        try {
            Class<?> ierClass = XposedHelpers.findClass("android.view.InputEventReceiver", lpparam.classLoader);
            XposedBridge.hookAllMethods(
                    ierClass,
                    "dispatchInputEvent",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length >= 2 && param.args[1] instanceof MotionEvent) {
                                MotionEvent event = (MotionEvent) param.args[1];
                                int action = event.getActionMasked();
                                boolean forceReset = (action == MotionEvent.ACTION_DOWN
                                        || action == MotionEvent.ACTION_UP
                                        || action == MotionEvent.ACTION_POINTER_DOWN
                                        || action == MotionEvent.ACTION_POINTER_UP);
                                boostAndResetTimer(forceReset);
                            } else if (param.args.length >= 1 && param.args[0] instanceof InputEvent) {
                                boostAndResetTimer(false);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("RefreshFix: InputEventReceiver hook error: " + t.getMessage());
        }

        // 6. 核心触摸入口 B：直接 Hook 父类 Dialog.class 的 dispatchTouchEvent
        try {
            XposedHelpers.findAndHookMethod(
                    Dialog.class,
                    "dispatchTouchEvent",
                    MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Dialog dialog = (Dialog) param.thisObject;
                            if (dialog.getWindow() != null) {
                                currentWindow = dialog.getWindow();
                            }
                            MotionEvent event = (MotionEvent) param.args[0];
                            if (event != null) {
                                int action = event.getActionMasked();
                                boolean forceReset = (action == MotionEvent.ACTION_DOWN
                                        || action == MotionEvent.ACTION_UP);
                                boostAndResetTimer(forceReset);
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        // 7. 核心打字入口 C：Hook 每次按键上屏必调的 getCurrentInputConnection（跨 APK 边界，绝不内联）
        try {
            XposedHelpers.findAndHookMethod(
                    "android.inputmethodservice.InputMethodService",
                    lpparam.classLoader,
                    "getCurrentInputConnection",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            currentIms = param.thisObject;
                            boostAndResetTimer(false);
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    private void boostAndResetTimer(boolean forceUpdateTimer) {
        long now = SystemClock.uptimeMillis();
        // 如果当前已经处于 120Hz 锁定状态，且距离上次续期不足 250ms，则直接跳过以节省开销
        if (isLocked120 && !forceUpdateTimer && (now - lastScheduleTime < INTERACTION_THROTTLE_MS)) {
            return;
        }
        lastScheduleTime = now;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyBoostOnMainThread();
        } else {
            getMainHandler().post(new Runnable() {
                @Override
                public void run() {
                    applyBoostOnMainThread();
                }
            });
        }
    }

    private void applyBoostOnMainThread() {
        Handler handler = getMainHandler();
        handler.removeCallbacks(idleReleaseTask);
        setWindowMinRefreshRate(getActiveWindow(), BOOST_FPS);
        isLocked120 = true;
        handler.postDelayed(idleReleaseTask, IDLE_TIMEOUT_MS);
    }

    private void setWindowMinRefreshRate(Window window, float minRate) {
        if (window == null) return;
        try {
            WindowManager.LayoutParams params = window.getAttributes();
            if (params == null) return;

            try {
                float currentMin = XposedHelpers.getFloatField(params, "preferredMinDisplayRefreshRate");
                float currentMax = XposedHelpers.getFloatField(params, "preferredMaxDisplayRefreshRate");
                if (Math.abs(currentMin - minRate) < 0.1f && Math.abs(currentMax - BOOST_FPS) < 0.1f) {
                    return;
                }
                XposedHelpers.setFloatField(params, "preferredMinDisplayRefreshRate", minRate);
                XposedHelpers.setFloatField(params, "preferredMaxDisplayRefreshRate", BOOST_FPS);
            } catch (Throwable t) {
                if (Math.abs(params.preferredRefreshRate - minRate) < 0.1f) {
                    return;
                }
                params.preferredRefreshRate = minRate;
            }

            window.setAttributes(params);
        } catch (Throwable t) {
            XposedBridge.log("RefreshFix: Failed to set refresh rate: " + t.getMessage());
        }
    }

    private void disableToolkitTypingFrameRateLimit(ClassLoader classLoader) {
        try {
            XposedHelpers.setStaticBooleanField(View.class, "sToolkitFrameRateTypingReadOnlyFlagValue", false);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> vriClass = XposedHelpers.findClass("android.view.ViewRootImpl", classLoader);
            XposedHelpers.setStaticBooleanField(vriClass, "sToolkitFrameRateTypingReadOnlyFlagValue", false);
        } catch (Throwable ignored) {
        }
    }

    private void hookFrameRateVotes(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllMethods(
                    SurfaceControl.Transaction.class,
                    "setFrameRateCategory",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                                int category = (Integer) param.args[1];
                                if (category >= CATEGORY_LOW && category < CATEGORY_HIGH) {
                                    param.args[1] = CATEGORY_HIGH;
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        try {
            XposedBridge.hookAllMethods(
                    SurfaceControl.Transaction.class,
                    "setFrameRate",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length >= 2 && param.args[1] instanceof Float) {
                                float rate = (Float) param.args[1];
                                if (rate > 0.0f && rate < BOOST_FPS) {
                                    param.args[1] = BOOST_FPS;
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        try {
            XposedBridge.hookAllMethods(
                    Surface.class,
                    "setFrameRate",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length >= 1 && param.args[0] instanceof Float) {
                                float rate = (Float) param.args[0];
                                if (rate > 0.0f && rate < BOOST_FPS) {
                                    param.args[0] = BOOST_FPS;
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }
}