package cn.gproject

import java.nio.file.Files
import java.nio.file.Path

private val monitorPath get() = Path.of(System.getProperty("user.home"),".gproject","monitor.json")
internal actual fun readMonitorPreferences(): String? = if (Files.exists(monitorPath)) Files.readString(monitorPath) else null
internal actual fun writeMonitorPreferences(value: String) {
    Files.createDirectories(monitorPath.parent)
    Files.writeString(monitorPath,value)
}
private val workspacePath get() = Path.of(System.getProperty("user.home"),".gproject","workspace.json")
internal actual fun readWorkspacePreferences(): String? = if(Files.exists(workspacePath)) Files.readString(workspacePath) else null
internal actual fun writeWorkspacePreferences(value: String) { Files.createDirectories(workspacePath.parent);Files.writeString(workspacePath,value) }
