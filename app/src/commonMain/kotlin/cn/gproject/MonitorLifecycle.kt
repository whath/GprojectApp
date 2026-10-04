package cn.gproject

import androidx.compose.runtime.Composable

@Composable
internal expect fun MonitorLifecycle(onActive: (Boolean) -> Unit)
