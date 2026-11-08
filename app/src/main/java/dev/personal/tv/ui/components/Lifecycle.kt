package dev.personal.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * App 看不见了（按主页键、进屏保、切到别的 App）→ [onHide]；又回到前台 → [onShow]。
 * 刚进页面时不算「回来」，[onShow] 只在 [onHide] 之后调。
 */
@Composable
fun OnAppHidden(onHide: () -> Unit, onShow: () -> Unit = {}) {
    val owner = LocalLifecycleOwner.current
    val hide by rememberUpdatedState(onHide)
    val show by rememberUpdatedState(onShow)
    DisposableEffect(owner) {
        var hidden = false
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { hidden = true; hide() }
                Lifecycle.Event.ON_START -> if (hidden) { hidden = false; show() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
}
