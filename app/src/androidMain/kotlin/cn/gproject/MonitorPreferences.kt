package cn.gproject

import android.content.Context

internal object MonitorStorage { var context: Context? = null }
internal actual fun readMonitorPreferences(): String? = MonitorStorage.context?.getSharedPreferences("monitor",Context.MODE_PRIVATE)?.getString("settings",null)
internal actual fun writeMonitorPreferences(value: String) {
    MonitorStorage.context?.getSharedPreferences("monitor",Context.MODE_PRIVATE)?.edit()?.putString("settings",value)?.apply()
}
internal actual fun readWorkspacePreferences(): String? = MonitorStorage.context?.getSharedPreferences("workspace",Context.MODE_PRIVATE)?.getString("settings",null)
internal actual fun writeWorkspacePreferences(value: String) {
    val context=checkNotNull(MonitorStorage.context) { "本地存储尚未初始化" }
    check(context.getSharedPreferences("workspace",Context.MODE_PRIVATE).edit().putString("settings",value).commit()) { "本地设置保存失败" }
}
