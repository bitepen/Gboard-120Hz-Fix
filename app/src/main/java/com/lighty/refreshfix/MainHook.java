package com.lighty.refreshfix;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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

    // DisplayModeDirector 核心引用
    private volatile Object mDisplayModeDirector;
    private volatile Object mDirectorLock;
    private volatile Handler mDirectorHandler;
    private Method mNotifySpecsChangedMethod;

    // DesiredDisplayModeSpecs 反射字段缓存
    private final List<Field> mRangesFields = new ArrayList<>();
    private Field mIdleConfigField;
    private Field mPhysicalRangeField;
    private Field mRenderRangeField;
    private Field mMinRateField;
    private Field mMaxRateField;
    private boolean mSpecsFieldsResolved = false;

    // 运行状态与一次性诊断日志标志
    private volatile boolean mIsImeBoostActive = false;
    private volatile long mLastMoveTime = 0L;
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
            hookPointerListener(lpparam.classLoader);
            hookDisplayModeDirector(lpparam.classLoader);
            XposedBridge.log(TAG + "System framework hooks installed successfully.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Initialization failed: " + t.getMessage());
        }
    }

    /**
     * 1. 监听全局触摸事件：直接通过 mCallbacks -> mDisplayContent 检查输入法窗口是否可见
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

                            if (isImeWindowVisible(param.thisObject)) {
                                onImeInteraction();
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
        );
    }

    /**
     * 2. 拦截 DisplayModeDirector.getDesiredDisplayModeSpecs
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

            // 修正方法名：AOSP 中带 Locked 后缀
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

    private boolean isImeWindowVisible(Object pointerListener) {
        try {
            // SystemGesturesPointerEventListener.mCallbacks 即为 DisplayPolicy 实例
            Object displayPolicy = XposedHelpers.getObjectField(pointerListener, "mCallbacks");
            if (displayPolicy == null) return false;

            Object displayContent = XposedHelpers.getObjectField(displayPolicy, "mDisplayContent");
            if (displayContent == null) return false;

            Object imeWindow = XposedHelpers.getObjectField(displayContent, "mInputMethodWindow");
            if (imeWindow == null) return false;

            // 使用 callMethod 自动向上遍历父类 WindowContainer/WindowState 的 isVisible()
            return (Boolean) XposedHelpers.callMethod(imeWindow, "isVisible");
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

    private void applyBoostToSpecs(Object specs) throws Exception {
        if (!mSpecsFieldsResolved) {
            Class<?> specsClass = specs.getClass();
            for (Field f : specsClass.getDeclaredFields()) {
                f.setAccessible(true);
                String typeName = f.getType().getSimpleName();
                if ("RefreshRateRanges".equals(typeName)) {
                    mRangesFields.add(f);
                    Object rangesObj = f.get(specs);
                    if (rangesObj != null && mPhysicalRangeField == null) {
                        mPhysicalRangeField = XposedHelpers.findFieldIfExists(rangesObj.getClass(), "physical");
                        mRenderRangeField = XposedHelpers.findFieldIfExists(rangesObj.getClass(), "render");
                        if (mPhysicalRangeField != null) {
                            Object rangeObj = mPhysicalRangeField.get(rangesObj);
                            if (rangeObj != null) {
                                mMinRateField = XposedHelpers.findFieldIfExists(rangeObj.getClass(), "min");
                                mMaxRateField = XposedHelpers.findFieldIfExists(rangeObj.getClass(), "max");
                            }
                        }
                    }
                } else if ("idleScreenRefreshRateConfig".equals(f.getName())) {
                    mIdleConfigField = f;
                }
            }
            mSpecsFieldsResolved = true;
        }

        for (Field rangesField : mRangesFields) {
            Object rangesObj = rangesField.get(specs);
            if (rangesObj != null && mPhysicalRangeField != null && mRenderRangeField != null) {
                lockSingleRange(mPhysicalRangeField.get(rangesObj));
                lockSingleRange(mRenderRangeField.get(rangesObj));
            }
        }

        if (mIdleConfigField != null) {
            mIdleConfigField.set(specs, null);
        }

        if (!mFirstSpecsLogged) {
            mFirstSpecsLogged = true;
            XposedBridge.log(TAG + "Boosted DesiredDisplayModeSpecs: " + specs.toString());
        }
    }

    private void lockSingleRange(Object rangeObj) throws Exception {
        if (rangeObj == null || mMinRateField == null || mMaxRateField == null) return;
        mMinRateField.setFloat(rangeObj, TARGET_FPS);
        mMaxRateField.setFloat(rangeObj, TARGET_FPS);
    }

    private Handler getWorkHandler() {
        if (mDirectorHandler != null) {
            return mDirectorHandler;
        }
        Looper mainLooper = Looper.getMainLooper();
        return mainLooper != null ? new Handler(mainLooper) : null;
    }
}