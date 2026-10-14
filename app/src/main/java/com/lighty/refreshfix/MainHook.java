package com.lighty.refreshfix;

import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
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
    // 停手 2.5 秒后自动解除 120Hz 下限锁定，恢复 LTPO 动态降频
    private static final long IDLE_TIMEOUT_MS = 2500L;

    // 懒加载 Handler，避免在 LSPosed 实例化模块时崩溃
    private Handler mainHandler;
    private Window currentWindow;
    private final Set<Class<?>> hookedDecorClasses = Collections.newSetFromMap(new WeakHashMap<>());

    private final Runnable idleReleaseTask = new Runnable() {
        @Override
        public void run() {
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

        // 1. 复刻四月份原版 Hook 点：showWindow 时立即开启 120Hz，并挂载根视图触摸监听
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

        // 2. 键盘隐藏时：立即取消倒计时并释放 120Hz 锁
        XposedHelpers.findAndHookMethod(
                "android.inputmethodservice.InputMethodService",
                lpparam.classLoader,
                "hideWindow",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        getMainHandler().removeCallbacks(idleReleaseTask);
                        setWindowMinRefreshRate(currentWindow, IDLE_FPS);
                    }
                }
        );

        // 3. 直接 Hook SoftInputWindow（而非被内联的父类 Dialog）拦截触摸事件
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
        } catch (Throwable t) {
            XposedBridge.log("RefreshFix: SoftInputWindow hook fallback to DecorView: " + t.getMessage());
        }
    }

    // 双保险：直接 Hook 键盘窗口 DecorView 的 dispatchTouchEvent，防内联且不漏任何点击
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
            // 手指按下或滑行输入：保持 120Hz，暂停空闲倒计时
            onUserInteraction(window, false);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            // 手指抬起：保持 120Hz，并重新开始 2.5 秒空闲倒计时
            onUserInteraction(window, true);
        }
    }

    private void onUserInteraction(Window window, boolean scheduleIdleTimeout) {
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
                // 只有当实际属性值需要改变时才调用 setAttributes，连续打字期间 0 额外开销
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