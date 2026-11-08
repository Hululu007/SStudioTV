package com.github.catvod.crawler;

import android.util.Log;

/** 插件查询宿主本地代理地址用 */
public class SpiderApi {

    public static volatile int port = 9978;

    public static void log(String msg) {
        if (msg != null) Log.d("SpiderApi", msg);
    }

    public static String getAddress(boolean local) {
        return "http://127.0.0.1:" + port;
    }
}
