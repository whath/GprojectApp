package cn.gproject

import androidx.compose.runtime.*

@Composable
internal actual fun MonitorLifecycle(onActive: (Boolean) -> Unit) {
    val callback by rememberUpdatedState(onActive)
    DisposableEffect(Unit) { callback(true); onDispose { callback(false) } }
}
