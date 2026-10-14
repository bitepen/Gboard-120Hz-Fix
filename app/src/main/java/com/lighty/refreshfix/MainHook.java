package com.lighty.refreshfix;

import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.google.android.inputmethod.latin";
    private static final float BOOST_FPS = 120.0f;
    private static final float IDLE_FPS = 0.0f;
    // 停手 2.5 秒后自动解除窗口 120Hz 下限锁定
    private static final long IDLE_TIMEOUT_MS = 2500L;

    // FrameRateCategory 常量 (Android 14/15/16+)
    private static final int CATEGORY_LOW = 2;        // 30Hz
    private static final int CATEGORY_NORMAL = 3;     // 60Hz
    private static final int CATEGORY_HIGH_HINT = 4;
    private static final int CATEGORY_HIGH = 5;       // 120Hz

    private Handler mainHandler;
    private Window currentWindow;
    private volatile boolean isTypingActive = false;
    private final Set<Class<?>> hookedDecorClasses = Collections.newSetFromMap(new WeakHashMap<>());

    private final Runnable idleReleaseTask = new Runnable() {
        @Override
        public void run() {
            isTypingActive = false;
            setWindowMinRefreshRate(currentWindow, IDLE_FPS);
        }
    };

    private Handler getMainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // 1. 拔除 Android 15/16+ 针对 TYPE_INPUT_METHOD 的打字 60Hz 强制降频开关
        disableToolkitTypingFrameRateLimit(lpparam.classLoader);

        // 2. 拦截 SurfaceControl 与 ViewRootImpl 的 60Hz (CATEGORY_NORMAL) 小面积重绘投票
        hookFrameRateVotes(lpparam.classLoader);

        // 3. 监听输入法窗口弹出：开启 120Hz 并绑定触摸监听
        XposedHelpers.findAndHookMethod(
                "android.inputmethodservice.InputMethodService",
                lpparam.classLoader,
                "showWindow",
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object ims = param.thisObject;
                        Dialog dialog = (Dialog) XposedHelpers.callMethod(ims, "getWindow");
                        if (dialog != null && dialog.getWindow() != null) {
                            currentWindow = dialog.getWindow();
                            hookDecorViewIfNeeded(currentWindow);
                            onUserInteraction(currentWindow, true);
                        }
                    }
                }
        );

        // 4. 键盘隐藏时：立即取消倒计时并释放 120Hz 锁
        XposedHelpers.findAndHookMethod(
                "android.inputmethodservice.InputMethodService",
                lpparam.classLoader,
                "hideWindow",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        isTypingActive = false;
                        getMainHandler().removeCallbacks(idleReleaseTask);
                        setWindowMinRefreshRate(currentWindow, IDLE_FPS);
                    }
                }
        );

        // 5. 拦截 SoftInputWindow 触摸事件
        try {
            XposedHelpers.findAndHookMethod(
                    "android.inputmethodservice.SoftInputWindow",
                    lpparam.classLoader,
                    "dispatchTouchEvent",
                    MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Dialog dialog = (Dialog) param.thisObject;
                            Window window = dialog.getWindow();
                            if (window != null) {
                                currentWindow = window;
                            }
                            MotionEvent event = (MotionEvent) param.args[0];
                            handleMotionEvent(currentWindow, event);
                        }
                    }
            );
        } catch (Throwable ignored) {
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
        // 拦截 SurfaceControl.Transaction.setFrameRateCategory (消除 60Hz 横跳的核心)
        try {
            XposedBridge.hookAllMethods(
                    SurfaceControl.Transaction.class,
                    "setFrameRateCategory",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 签名: setFrameRateCategory(SurfaceControl sc, int category, boolean ...)
                            if (isTypingActive && param.args.length >= 2 && param.args[1] instanceof Integer) {
                                int category = (Integer) param.args[1];
                                // 拦截 LOW(2, 30Hz)、NORMAL(3, 60Hz)、HIGH_HINT(4)，统一升为 HIGH(5, 120Hz)
                                // 保留 DEFAULT(0) 和 NO_PREFERENCE(1)，确保停手瞬间依然能掉回 24Hz/1Hz 省电
                                if (category >= CATEGORY_LOW && category < CATEGORY_HIGH) {
                                    param.args[1] = CATEGORY_HIGH;
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        // 拦截 SurfaceControl.Transaction.setFrameRate
        try {
            XposedBridge.hookAllMethods(
                    SurfaceControl.Transaction.class,
                    "setFrameRate",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isTypingActive && param.args.length >= 2 && param.args[1] instanceof Float) {
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

        // 拦截 ViewRootImpl 内部的 setPreferredFrameRateCategory 投票汇总
        try {
            Class<?> vriClass = XposedHelpers.findClass("android.view.ViewRootImpl", classLoader);
            XposedBridge.hookAllMethods(
                    vriClass,
                    "setPreferredFrameRateCategory",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isTypingActive && param.args.length >= 1 && param.args[0] instanceof Integer) {
                                int category = (Integer) param.args[0];
                                if (category >= CATEGORY_LOW && category < CATEGORY_HIGH) {
                                    param.args[0] = CATEGORY_HIGH;
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }

        // 兜底拦截 Surface.setFrameRate
        try {
            XposedBridge.hookAllMethods(
                    Surface.class,
                    "setFrameRate",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isTypingActive && param.args.length >= 1 && param.args[0] instanceof Float) {
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

    private void hookDecorViewIfNeeded(Window window) {
        if (window == null) return;
        View decor = window.peekDecorView();
        if (decor == null) return;

        Class<?> decorClass = decor.getClass();
        if (hookedDecorClasses.contains(decorClass)) return;
        hookedDecorClasses.add(decorClass);

        try {
            XposedHelpers.findAndHookMethod(
                    decorClass,
                    "dispatchTouchEvent",
                    MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (currentWindow != null && param.thisObject == currentWindow.peekDecorView()) {
                                MotionEvent event = (MotionEvent) param.args[0];
                                handleMotionEvent(currentWindow, event);
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    private void handleMotionEvent(Window window, MotionEvent event) {
        if (window == null || event == null) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            onUserInteraction(window, false);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            onUserInteraction(window, true);
        }
    }

    private void onUserInteraction(Window window, boolean scheduleIdleTimeout) {
        isTypingActive = true;
        Handler handler = getMainHandler();
        handler.removeCallbacks(idleReleaseTask);
        setWindowMinRefreshRate(window, BOOST_FPS);
        if (scheduleIdleTimeout) {
            handler.postDelayed(idleReleaseTask, IDLE_TIMEOUT_MS);
        }
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
}