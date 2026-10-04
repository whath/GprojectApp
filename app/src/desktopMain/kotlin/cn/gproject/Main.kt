package cn.gproject

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val model = remember { MarketViewModel() }
    DisposableEffect(Unit) { onDispose { model.close() } }
    Window(
        onCloseRequest = ::exitApplication,
        title = "观市 · Gproject",
        icon = painterResource("brand-icon.png"),
        state = rememberWindowState(width = 1240.dp, height = 860.dp),
    ) {
        App(model)
    }
}
