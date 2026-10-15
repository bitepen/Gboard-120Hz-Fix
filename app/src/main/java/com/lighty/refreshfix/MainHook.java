package com.lighty.refreshfix;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[RefreshFix] ";
    private static final String SYSTEM_FRAMEWORK = "android";

    private static final float TARGET_FPS = 120.0f;
    // 停手 600ms 后自动释放 120Hz 下限锁，恢复 LTPO 动态降频
    private static final long IDLE_TIMEOUT_MS = 600L;
    // MOVE 事件节流间隔，避免滑动输入时高频操作 Handler
    private static final long MOVE_THROTTLE_MS = 200L;

    // 系统服务单例引用
    private volatile Object mDefaultDisplayContent;
    private volatile Object mDisplayModeDirector;
    private volatile Handler mDirectorHandler;

    // 反射缓存（仅首次解析一次，运行时零查找开销）
    private Field mImeWindowField;
    private Method mIsVisibleMethod;
    private Method mNotifySpecsChangedMethod;

    private Field mPrimaryRangesField;
    private Field mAppRequestRangesField;
    private Field mIdleConfigField;
    private Field mPhysicalRangeField;
    private Field mRenderRangeField;
    private Field mMinRateField;
    private Field mMaxRateField;
    private boolean mSpecsFieldsResolved = false;

    // 运行状态控制
    private volatile boolean mIsImeBoostActive = false;
    private volatile long mLastMoveTime = 0L;
    private boolean mFirstBoostLogged = false;

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
            hookDisplayPolicy(lpparam.classLoader);
            hookPointerListener(lpparam.classLoader);
            hookDisplayModeDirector(lpparam.classLoader);
            XposedBridge.log(TAG + "System framework hooks installed successfully.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Initialization failed: " + t.getMessage());
        }
    }

    /**
     * 1. 捕获默认主屏幕的 DisplayContent 引用，用于后续快速检查 mInputMethodWindow.isVisible()
     */
    private void hookDisplayPolicy(ClassLoader classLoader) {
        Class<?> displayPolicyClass = XposedHelpers.findClass(
                "com.android.server.wm.DisplayPolicy", classLoader);

        XposedBridge.hookAllConstructors(displayPolicyClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object displayContent = XposedHelpers.getObjectField(param.thisObject, "mDisplayContent");
                    if (displayContent != null) {
                        boolean isDefault = XposedHelpers.getBooleanField(displayContent, "isDefaultDisplay");
                        if (isDefault) {
                            mDefaultDisplayContent = displayContent;
                            mImeWindowField = XposedHelpers.findField(displayContent.getClass(), "mInputMethodWindow");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * 2. 监听全局触摸事件：仅在输入法窗口可见时触发/续期 120Hz 锁定
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
     * 3. 拦截 DisplayModeDirector：在打字活跃期内将主屏刷新率范围锁定为 [120Hz, 120Hz]
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
                        } catch (Throwable ignored) {
                        }
                    }
                }
        );
    }

    private void initDirectorReferences(Object director) {
        try {
            mDisplayModeDirector = director;
            mDirectorHandler = (Handler) XposedHelpers.getObjectField(director, "mHandler");
            mNotifySpecsChangedMethod = XposedHelpers.findMethodExact(
                    director.getClass(), "notifyDesiredDisplayModeSpecsChanged");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to init DisplayModeDirector refs: " + t.getMessage());
        }
    }

    private boolean isImeWindowVisible() {
        try {
            Object dc = mDefaultDisplayContent;
            if (dc == null || mImeWindowField == null) return false;

            Object imeWindow = mImeWindowField.get(dc);
            if (imeWindow == null) return false;

            if (mIsVisibleMethod == null) {
                mIsVisibleMethod = XposedHelpers.findMethodExact(imeWindow.getClass(), "isVisible");
            }
            return (Boolean) mIsVisibleMethod.invoke(imeWindow);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void onImeInteraction() {
        Handler handler = getWorkHandler();
        if (handler == null) return;

        handler.removeCallbacks(mReleaseBoostTask);
        if (!mIsImeBoostActive) {
            mIsImeBoostActive = true;
            triggerSpecsUpdate();
            if (!mFirstBoostLogged) {
                mFirstBoostLogged = true;
                XposedBridge.log(TAG + "IME 120Hz boost activated (first trigger verified).");
            }
        }
        handler.postDelayed(mReleaseBoostTask, IDLE_TIMEOUT_MS);
    }

    private void triggerSpecsUpdate() {
        try {
            if (mDisplayModeDirector != null && mNotifySpecsChangedMethod != null) {
                mNotifySpecsChangedMethod.invoke(mDisplayModeDirector);
            }
        } catch (Throwable ignored) {
        }
    }

    private void applyBoostToSpecs(Object specs) throws Exception {
        if (!mSpecsFieldsResolved) {
            Class<?> specsClass = specs.getClass();
            mPrimaryRangesField = findFieldSafe(specsClass, "primary", "primaryRanges");
            mAppRequestRangesField = findFieldSafe(specsClass, "appRequest", "appRequestRanges");
            mIdleConfigField = findFieldSafe(specsClass, "idleScreenRefreshRateConfig");

            if (mPrimaryRangesField != null) {
                Object primaryObj = mPrimaryRangesField.get(specs);
                if (primaryObj != null) {
                    Class<?> rangesClass = primaryObj.getClass();
                    mPhysicalRangeField = findFieldSafe(rangesClass, "physical");
                    mRenderRangeField = findFieldSafe(rangesClass, "render");
                    if (mPhysicalRangeField != null) {
                        Object rangeObj = mPhysicalRangeField.get(primaryObj);
                        if (rangeObj != null) {
                            mMinRateField = findFieldSafe(rangeObj.getClass(), "min");
                            mMaxRateField = findFieldSafe(rangeObj.getClass(), "max");
                        }
                    }
                }
            }
            mSpecsFieldsResolved = true;
        }

        if (mPrimaryRangesField != null) {
            lockRefreshRateRanges(mPrimaryRangesField.get(specs));
        }
        if (mAppRequestRangesField != null) {
            lockRefreshRateRanges(mAppRequestRangesField.get(specs));
        }
        // Android 15/16+: 打字活跃期内临时置空空闲降频配置，防止按键间隙掉回 24Hz
        if (mIdleConfigField != null) {
            mIdleConfigField.set(specs, null);
        }
    }

    private void lockRefreshRateRanges(Object rangesObj) throws Exception {
        if (rangesObj == null || mPhysicalRangeField == null || mRenderRangeField == null) return;
        lockSingleRange(mPhysicalRangeField.get(rangesObj));
        lockSingleRange(mRenderRangeField.get(rangesObj));
    }

    private void lockSingleRange(Object rangeObj) throws Exception {
        if (rangeObj == null || mMinRateField == null || mMaxRateField == null) return;
        mMinRateField.setFloat(rangeObj, TARGET_FPS);
        mMaxRateField.setFloat(rangeObj, TARGET_FPS);
    }

    private Field findFieldSafe(Class<?> clazz, String... names) {
        for (String name : names) {
            Field f = XposedHelpers.findFieldIfExists(clazz, name);
            if (f != null) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private Handler getWorkHandler() {
        if (mDirectorHandler != null) {
            return mDirectorHandler;
        }
        Looper mainLooper = Looper.getMainLooper();
        return mainLooper != null ? new Handler(mainLooper) : null;
    }
}