package cn.gproject

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.*

@Composable
internal fun ImportantCalendarBrowser(agenda: ImportantAgenda,category: String) {
    var country by rememberSaveable(category) { mutableStateOf("") }
    var days by rememberSaveable(category) { mutableStateOf(30) }
    var count by rememberSaveable(category,country,days) { mutableStateOf(30) }
    val uri=LocalUriHandler.current
    var linkFailed by remember { mutableStateOf(false) }
    val today=Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date
    val last=today.plus(DatePeriod(days=days)).toString()
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            listOf("" to "中美全部","CN" to "中国","US" to "美国").forEach { (key,label) -> FilterChip(country==key,{country=key},label={Text(label)}) }
            listOf(7,30,90).forEach { n -> FilterChip(days==n,{days=n},label={Text("未来${n}天")}) }
        }
        if(agenda.note.isNotBlank()) Text(agenda.note,fontSize=11.sp,color=Muted,lineHeight=18.sp,modifier=Modifier.testTag("important-calendar-note"))
        if(linkFailed) Text("暂时无法打开来源页面，请稍后重试。",fontSize=11.sp,color=Muted)
        val rows=agenda.items.filter { it.category==category && (country.isBlank() || it.country==country) &&
            (it.time?.let { time -> Instant.parse(time).toLocalDateTime(TimeZone.of("Asia/Shanghai")).date.toString() } ?: it.start)<=last }
        rows.take(count).forEach { event ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("important-event-${event.id}"),colors=CardDefaults.outlinedCardColors(containerColor=androidx.compose.ui.graphics.Color.White)) {
                Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    val time=event.time?.let { stamp ->
                        val local=Instant.parse(stamp).toLocalDateTime(TimeZone.of("Asia/Shanghai"))
                        "${local.date} ${local.hour.toString().padStart(2,'0')}:${local.minute.toString().padStart(2,'0')} 北京时间"
                    } ?: (if(event.start==event.end) event.start else "${event.start} — ${event.end}")+" · "+if(event.country=="CN") "中国当地日期" else "美国当地日期"
                    Text(time,fontSize=11.sp,color=Muted)
                    Text(event.title,fontSize=15.sp,fontWeight=FontWeight.SemiBold,lineHeight=23.sp)
                    Text("${if(event.country=="CN") "中国" else "美国"} · ${event.issuer}${if(event.official) " · 官方日程" else " · 补充来源"}",fontSize=10.sp,color=Muted)
                    if(event.stale) Text("当前为保留日程，需留意发布机关的调整。",fontSize=10.sp,color=Muted)
                    TextButton(onClick={try { uri.openUri(event.url) } catch(_: Exception) { linkFailed=true }},contentPadding=PaddingValues(0.dp)) { Text("核对日程原文 ↗",fontSize=11.sp) }
                }
            }
        }
        if(rows.size>count) OutlinedButton(onClick={count+=30},modifier=Modifier.fillMaxWidth()) { Text("查看更多日程") }
        if(rows.isEmpty()) Text("所选范围暂无已取得的${if(category=="meeting") "重要会议" else "重要数据公布"}日程；未公布日期不作推算。",fontSize=12.sp,color=Muted,lineHeight=20.sp)
        Text(if(category=="meeting") "仅列有明确官方日期的中央重要会议、FOMC 等；中央经济工作会议及后续会议，待官方公布后补入。美国日期型会议不转换成推定的北京时间。"
            else "重点关注：中国 GDP/工业/消费/投资、PMI、CPI、PPI；美国 GDP、PCE、就业与通胀。按已取得的官方或明确标注的补充日程展示，不保证穷尽。",fontSize=10.sp,color=Muted,lineHeight=18.sp)
    }
}
