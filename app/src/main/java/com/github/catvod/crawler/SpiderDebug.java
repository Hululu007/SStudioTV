package com.github.catvod.crawler;

import android.util.Log;

public class SpiderDebug {

    private static final String TAG = "Spider";

    public static void log(Throwable th) {
        if (th != null) Log.w(TAG, th);
    }

    public static void log(String msg) {
        if (msg != null) Log.d(TAG, msg);
    }
}
