package com.lighty.refreshfix;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[RefreshFix] ";
    private static final String SYSTEM_FRAMEWORK = "android";

    private static final float TARGET_FPS = 120.0f;
    private static final long IDLE_TIMEOUT_MS = 600L;
    private static final long MOVE_THROTTLE_MS = 200L;

    // 弱引用保存所有 TYPE_INPUT_METHOD 类型的 WindowState，自动随窗口销毁回收
    private final Set<Object> mImeWindows = Collections.newSetFromMap(new WeakHashMap<>());

    // DisplayModeDirector 核心引用
    private volatile Object mDisplayModeDirector;
    private volatile Object mDirectorLock;
    private volatile Handler mDirectorHandler;
    private Method mNotifySpecsChangedMethod;

    // 运行状态与一次性诊断日志标志
    private volatile boolean mIsImeBoostActive = false;
    private volatile long mLastMoveTime = 0L;
    private boolean mFirstWindowCapturedLogged = false;
    private boolean mFirstTouchLogged = false;
    private boolean mFirstSpecsLogged = false;
    private boolean mImeVisibilityErrorLogged = false;

    private final Runnable mReleaseBoostTask = new Runnable() {
        @Override
        public void run() {
            if (mIsImeBoostActive) {
                mIsImeBoostActive = false;
                triggerSpecsUpdate();
            }
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_FRAMEWORK.equals(lpparam.packageName)) {
            return;
        }

        try {
            hookWindowState(lpparam.classLoader);
            hookPointerListener(lpparam.classLoader);
            hookDisplayModeDirector(lpparam.classLoader);
            XposedBridge.log(TAG + "System framework hooks installed successfully.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Initialization failed: " + t.getMessage());
        }
    }

    /**
     * 1. 直接在 WindowState 构造时通过公开的 LayoutParams.type == TYPE_INPUT_METHOD 捕获输入法窗口
     */
    private void hookWindowState(ClassLoader classLoader) {
        Class<?> windowStateClass = XposedHelpers.findClass(
                "com.android.server.wm.WindowState", classLoader);

        XposedBridge.hookAllConstructors(windowStateClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    WindowManager.LayoutParams lp = null;
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg instanceof WindowManager.LayoutParams) {
                                lp = (WindowManager.LayoutParams) arg;
                                break;
                            }
                        }
                    }
                    if (lp == null) {
                        Object attrs = XposedHelpers.getObjectField(param.thisObject, "mAttrs");
                        if (attrs instanceof WindowManager.LayoutParams) {
                            lp = (WindowManager.LayoutParams) attrs;
                        }
                    }

                    if (lp != null && lp.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD) {
                        synchronized (mImeWindows) {
                            mImeWindows.add(param.thisObject);
                        }
                        if (!mFirstWindowCapturedLogged) {
                            mFirstWindowCapturedLogged = true;
                            XposedBridge.log(TAG + "Captured IME WindowState (" + lp.packageName + ").");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * 2. 监听全局触摸事件：当输入法 WindowState 处于可见状态时激活 120Hz
     */
    private void hookPointerListener(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(
                "com.android.server.wm.SystemGesturesPointerEventListener",
                classLoader,
                "onPointerEvent",
                MotionEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            MotionEvent event = (MotionEvent) param.args[0];
                            if (event == null) return;

                            int action = event.getActionMasked();
                            if (action == MotionEvent.ACTION_MOVE) {
                                long now = SystemClock.uptimeMillis();
                                if (now - mLastMoveTime < MOVE_THROTTLE_MS) {
                                    return;
                                }
                                mLastMoveTime = now;
                            } else if (action != MotionEvent.ACTION_DOWN
                                    && action != MotionEvent.ACTION_UP
                                    && action != MotionEvent.ACTION_POINTER_DOWN
                                    && action != MotionEvent.ACTION_POINTER_UP) {
                                return;
                            }

                            if (isImeWindowVisible()) {
                                onImeInteraction();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
        );
    }

    /**
     * 3. 拦截 DisplayModeDirector.getDesiredDisplayModeSpecs
     */
    private void hookDisplayModeDirector(ClassLoader classLoader) {
        Class<?> directorClass = XposedHelpers.findClassIfExists(
                "com.android.server.display.mode.DisplayModeDirector", classLoader);
        if (directorClass == null) {
            directorClass = XposedHelpers.findClass(
                    "com.android.server.display.DisplayModeDirector", classLoader);
        }

        XposedHelpers.findAndHookMethod(
                directorClass,
                "getDesiredDisplayModeSpecs",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (mDisplayModeDirector == null) {
                                initDirectorReferences(param.thisObject);
                            }

                            int displayId = (Integer) param.args[0];
                            if (!mIsImeBoostActive || displayId != Display.DEFAULT_DISPLAY) {
                                return;
                            }

                            Object specs = param.getResult();
                            if (specs != null) {
                                applyBoostToSpecs(specs);
                            }
                        } catch (Throwable t) {
                            if (!mFirstSpecsLogged) {
                                mFirstSpecsLogged = true;
                                XposedBridge.log(TAG + "applyBoostToSpecs error: " + t.getMessage());
                            }
                        }
                    }
                }
        );
    }

    private void initDirectorReferences(Object director) {
        try {
            mDisplayModeDirector = director;
            mDirectorLock = XposedHelpers.getObjectField(director, "mLock");
            mDirectorHandler = (Handler) XposedHelpers.getObjectField(director, "mHandler");

            mNotifySpecsChangedMethod = XposedHelpers.findMethodExactIfExists(
                    director.getClass(), "notifyDesiredDisplayModeSpecsChangedLocked");
            if (mNotifySpecsChangedMethod == null) {
                mNotifySpecsChangedMethod = XposedHelpers.findMethodExactIfExists(
                        director.getClass(), "notifyDesiredDisplayModeSpecsChanged");
            }
            if (mNotifySpecsChangedMethod != null) {
                mNotifySpecsChangedMethod.setAccessible(true);
                XposedBridge.log(TAG + "DisplayModeDirector bound: " + mNotifySpecsChangedMethod.getName());
            } else {
                XposedBridge.log(TAG + "Warning: notifyDesiredDisplayModeSpecsChangedLocked not found.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to init DisplayModeDirector refs: " + t.getMessage());
        }
    }

    private boolean isImeWindowVisible() {
        try {
            synchronized (mImeWindows) {
                for (Object win : mImeWindows) {
                    if (win == null) continue;
                    Boolean visible = (Boolean) XposedHelpers.callMethod(win, "isVisible");
                    if (visible != null && visible) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            if (!mImeVisibilityErrorLogged) {
                mImeVisibilityErrorLogged = true;
                XposedBridge.log(TAG + "isImeWindowVisible error: " + t.getMessage());
            }
            return false;
        }
    }

    private void onImeInteraction() {
        Handler handler = getWorkHandler();
        if (handler == null) return;

        handler.removeCallbacks(mReleaseBoostTask);
        if (!mIsImeBoostActive) {
            mIsImeBoostActive = true;
            if (!mFirstTouchLogged) {
                mFirstTouchLogged = true;
                XposedBridge.log(TAG + "IME touch detected while visible, activating 120Hz boost.");
            }
            triggerSpecsUpdate();
        }
        handler.postDelayed(mReleaseBoostTask, IDLE_TIMEOUT_MS);
    }

    private void triggerSpecsUpdate() {
        try {
            if (mDisplayModeDirector != null && mNotifySpecsChangedMethod != null) {
                if (mDirectorLock != null) {
                    synchronized (mDirectorLock) {
                        mNotifySpecsChangedMethod.invoke(mDisplayModeDirector);
                    }
                } else {
                    mNotifySpecsChangedMethod.invoke(mDisplayModeDirector);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "triggerSpecsUpdate failed: " + t.getMessage());
        }
    }

    /**
     * 递归遍历 DesiredDisplayModeSpecs 内部的所有 RefreshRateRange 结构并将 min/max 设为 120.0f
     */
    private void applyBoostToSpecs(Object specs) throws Exception {
        String beforeStr = !mFirstSpecsLogged ? specs.toString() : null;

        for (Field f : specs.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object val = f.get(specs);
            if (val == null) continue;

            if (f.getName().contains("idle") || f.getType().getSimpleName().contains("Idle")) {
                f.set(specs, null);
                continue;
            }

            if (!lockRangeObject(val)) {
                for (Field subField : val.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(subField.getModifiers())) continue;
                    subField.setAccessible(true);
                    Object subVal = subField.get(val);
                    if (subVal != null) {
                        lockRangeObject(subVal);
                    }
                }
            }
        }

        if (!mFirstSpecsLogged) {
            mFirstSpecsLogged = true;
            XposedBridge.log(TAG + "Boosted Specs: before=[" + beforeStr + "] -> after=[" + specs.toString() + "]");
        }
    }

    private boolean lockRangeObject(Object obj) {
        try {
            Field minField = XposedHelpers.findFieldIfExists(obj.getClass(), "min");
            Field maxField = XposedHelpers.findFieldIfExists(obj.getClass(), "max");
            if (minField != null && maxField != null
                    && minField.getType() == float.class && maxField.getType() == float.class) {
                minField.setAccessible(true);
                maxField.setAccessible(true);
                minField.setFloat(obj, TARGET_FPS);
                maxField.setFloat(obj, TARGET_FPS);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private Handler getWorkHandler() {
        if (mDirectorHandler != null) {
            return mDirectorHandler;
        }
        Looper mainLooper = Looper.getMainLooper();
        return mainLooper != null ? new Handler(mainLooper) : null;
    }
}