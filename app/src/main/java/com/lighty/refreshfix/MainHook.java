package com.lighty.refreshfix;

import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
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
    private static final float BOOST_FPS = 120.0f;
    private static final float IDLE_FPS = 0.0f;
    // 停手 1.5 秒后自动释放 120Hz 锁，交还给系统 LTPO 降频
    private static final long IDLE_DELAY_MS = 1500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Window currentImeWindow = null;
    private boolean isBoosted = false;

    private final Runnable releaseBoostRunnable = new Runnable() {
        @Override
        public void run() {
            applyWindowRefreshRate(currentImeWindow, false);
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        // 1. 监听输入法窗口弹出：绑定 Window 并在弹出动画期间升频 120Hz
        XposedHelpers.findAndHookMethod(
                "android.inputmethodservice.InputMethodService",
                lpparam.classLoader,
                "showWindow",
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Dialog dialog = (Dialog) XposedHelpers.callMethod(param.thisObject, "getWindow");
                        if (dialog != null && dialog.getWindow() != null) {
                            currentImeWindow = dialog.getWindow();
                            triggerBoostWithTimeout();
                        }
                    }
                }
        );

        // 2. 监听输入法窗口收起：立即解除 120Hz 锁定
        XposedHelpers.findAndHookMethod(
                "android.inputmethodservice.InputMethodService",
                lpparam.classLoader,
                "hideWindow",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        mainHandler.removeCallbacks(releaseBoostRunnable);
                        applyWindowRefreshRate(currentImeWindow, false);
                    }
                }
        );

        // 3. 监听键盘触摸事件：打字时维持 120Hz，停手 1.5 秒后自动恢复默认
        XposedHelpers.findAndHookMethod(
                Dialog.class,
                "dispatchTouchEvent",
                MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        MotionEvent event = (MotionEvent) param.args[0];
                        if (event == null) return;

                        Dialog dialog = (Dialog) param.thisObject;
                        if (dialog.getWindow() != null) {
                            currentImeWindow = dialog.getWindow();
                        }

                        int action = event.getActionMasked();
                        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                            // 手指按下或滑行输入：取消降频倒计时，若尚未升频则立即锁 120Hz
                            mainHandler.removeCallbacks(releaseBoostRunnable);
                            if (!isBoosted) {
                                applyWindowRefreshRate(currentImeWindow, true);
                            }
                        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                            // 手指抬起：重新开始 1.5 秒倒计时
                            mainHandler.removeCallbacks(releaseBoostRunnable);
                            mainHandler.postDelayed(releaseBoostRunnable, IDLE_DELAY_MS);
                        }
                    }
                }
        );

        // 4. 拦截 Android 14+ ViewRootImpl 的 FrameRateCategory 小面积重绘降频投票
        hookFrameRateCategory();
    }

    private void triggerBoostWithTimeout() {
        mainHandler.removeCallbacks(releaseBoostRunnable);
        if (!isBoosted) {
            applyWindowRefreshRate(currentImeWindow, true);
        }
        mainHandler.postDelayed(releaseBoostRunnable, IDLE_DELAY_MS);
    }

    private void applyWindowRefreshRate(Window window, boolean boost) {
        if (window == null) return;
        if (isBoosted == boost) return; // 状态未变时不重复调用 setAttributes，避免多余 IPC

        try {
            WindowManager.LayoutParams params = window.getAttributes();
            float targetRate = boost ? BOOST_FPS : IDLE_FPS;

            try {
                XposedHelpers.setFloatField(params, "preferredMinDisplayRefreshRate", targetRate);
                XposedHelpers.setFloatField(params, "preferredMaxDisplayRefreshRate", BOOST_FPS);
            } catch (Throwable ignored) {
            }
            params.preferredRefreshRate = targetRate;

            window.setAttributes(params);
            isBoosted = boost;
        } catch (Throwable t) {
            XposedBridge.log("RefreshFix: applyWindowRefreshRate failed: " + t.getMessage());
        }
    }

    private void hookFrameRateCategory() {
        try {
            // Android 14/15+ 中，FRAME_RATE_CATEGORY_LOW = 2, NORMAL(60Hz) = 3, HIGH_HINT = 4, HIGH(120Hz) = 5
            XposedBridge.hookAllMethods(
                    SurfaceControl.Transaction.class,
                    "setFrameRateCategory",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isBoosted && param.args.length >= 2 && param.args[1] instanceof Integer) {
                                int category = (Integer) param.args[1];
                                // 当处于打字活跃期且系统投出 LOW(2) 或 NORMAL(3) 时，强行改为 HIGH(5)
                                if (category == 2 || category == 3) {
                                    param.args[1] = 5;
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {
        }
    }
}