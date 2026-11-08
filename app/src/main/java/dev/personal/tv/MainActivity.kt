package dev.personal.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.personal.tv.source.Lab
import dev.personal.tv.spider.SpiderClient
import dev.personal.tv.ui.AppNav
import dev.personal.tv.ui.theme.AppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var labReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 插件进程（含本地代理 9978）先拉起来
        SpiderClient.bind(this)
        setContent {
            AppTheme {
                AppNav()
            }
        }
        // 只在 debug 包里：adb 广播驱动源调试，批量试站点
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            labReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val cfg = i.getStringExtra("cfg") ?: dev.personal.tv.source.Sources.url ?: return
                    i.getStringExtra("purl")?.let { u ->
                        lifecycleScope.launch { Lab.parseTest(applicationContext, cfg, i.getStringExtra("flag") ?: "qq", u, i.getBooleanExtra("play", false)) }
                        return
                    }
                    val site = i.getStringExtra("site") ?: return
                    val wd = i.getStringExtra("wd") ?: "繁花"
                    lifecycleScope.launch { Lab.auto(applicationContext, cfg, site, wd, i.getBooleanExtra("play", false)) }
                }
            }
            ContextCompat.registerReceiver(this, labReceiver, IntentFilter("dev.personal.tv.LAB"), ContextCompat.RECEIVER_EXPORTED)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { SpiderClient.reclaimPort() }
    }

    override fun onStop() {
        super.onStop()
        // 不用 lifecycleScope：onStop 之后它还在，但进程随时可能被冻结，直接起个线程
        Thread { SpiderClient.releasePort() }.start()
    }

    override fun onDestroy() {
        labReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }
}
