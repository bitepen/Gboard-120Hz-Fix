package com.lighty.refreshfix;

import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.google.android.inputmethod.latin")) {
            return;
        }

        XposedHelpers.findAndHookMethod(
                "android.inputmethodservice.InputMethodService",
                lpparam.classLoader,
                "showWindow",
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object inputMethodService = param.thisObject;
                        
                        Dialog dialog = (Dialog) XposedHelpers.callMethod(inputMethodService, "getWindow");
                        
                        if (dialog != null && dialog.getWindow() != null) {
                            Window window = dialog.getWindow();
                            WindowManager.LayoutParams params = window.getAttributes();
                            
                            try {
                                XposedHelpers.setFloatField(params, "preferredMinDisplayRefreshRate", 120.0f);
                                XposedHelpers.setFloatField(params, "preferredMaxDisplayRefreshRate", 120.0f);
                            } catch (Throwable t) {
                                params.preferredRefreshRate = 120.0f;
                            }
                            
                            window.setAttributes(params);
                        }
                    }
                }
        );
    }
}