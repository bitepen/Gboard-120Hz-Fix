package com.lighty.refreshfix;

import android.app.Dialog;
import android.os.Build;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.Window;
import android.view.WindowManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.google.android.inputmethod.latin";
    private static final float TARGET_FPS = 120.0f;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // 1. 仅在输入法窗口初始化时放开最高刷新率上限，绝不锁死 Min 刷新率，且避免重复 IPC
        hookWindowMaxRefreshRate(lpparam.classLoader);

        // 2. 核心：拦截 ViewRootImpl 向 SurfaceFlinger 提交的 60Hz (NORMAL) 降频投票
        hookSurfaceFrameRateVoting();
    }

    private void hookWindowMaxRefreshRate(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(
                "android.inputmethodservice.InputMethodService",
                classLoader,
                "onWindowShown", // 比 showWindow(boolean) 触发频次更低、时机更精准
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Dialog dialog = (Dialog) XposedHelpers.callMethod(param.thisObject, "getWindow");
                        if (dialog == null) return;

                        Window window = dialog.getWindow();
                        if (window == null) return;

                        WindowManager.LayoutParams params = window.getAttributes();
                        try {
                            float currentMax = XposedHelpers.getFloatField(params, "preferredMaxDisplayRefreshRate");
                            float currentMin = XposedHelpers.getFloatField(params, "preferredMinDisplayRefreshRate");

                            // 只有当上限还不是 120Hz，或者下限被意外锁死时才修改，避免无意义的 relayoutWindow IPC
                            if (currentMax != TARGET_FPS || currentMin != 0.0f) {
                                // 下限设为 0.0f（交给 LTPO 自动降频），只把上限设为 120.0f
                                XposedHelpers.setFloatField(params, "preferredMinDisplayRefreshRate", 0.0f);
                                XposedHelpers.setFloatField(params, "preferredMaxDisplayRefreshRate", TARGET_FPS);
                                window.setAttributes(params);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
        );
    }

    private void hookSurfaceFrameRateVoting() {
        // 拦截 SurfaceControl.Transaction.setFrameRate (Android 11+ ViewRootImpl 投票的主通道)
        try {
            XposedBridge.hookAllMethods(
                    SurfaceControl.Transaction.class,
                    "setFrameRate",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // 参数签名通常为: (SurfaceControl sc, float frameRate, int compatibility, ...)
                            if (param.args.length >= 2 && param.args[1] instanceof Float) {
                                float votedRate = (Float) param.args[1];
                                // 当 Gboard 因为重绘小面积按键而主动投出 (0, 120) 之间的降频票（如 60Hz）时：
                                // 将其改为 120.0f；但如果是 0.0f（代表停手后的 Idle 释放投票），则原样放行！
                                if (votedRate > 0.0f && votedRate < TARGET_FPS) {
                                    param.args[1] = TARGET_FPS;
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("RefreshFix: Failed to hook Transaction.setFrameRate: " + t.getMessage());
        }

        // 兜底拦截 Surface.setFrameRate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                XposedBridge.hookAllMethods(
                        Surface.class,
                        "setFrameRate",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.args.length >= 1 && param.args[0] instanceof Float) {
                                    float votedRate = (Float) param.args[0];
                                    if (votedRate > 0.0f && votedRate < TARGET_FPS) {
                                        param.args[0] = TARGET_FPS;
                                    }
                                }
                            }
                        }
                );
            } catch (Throwable t) {
                XposedBridge.log("RefreshFix: Failed to hook Surface.setFrameRate: " + t.getMessage());
            }
        }
    }
}