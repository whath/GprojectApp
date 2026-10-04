package cn.gproject

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.*
import kotlin.math.*

internal fun localTime(value: String): String = runCatching {
    val date = Instant.parse(value).toLocalDateTime(TimeZone.of("Asia/Shanghai"))
    "${date.date} ${date.hour.toString().padStart(2,'0')}:${date.minute.toString().padStart(2,'0')}"
}.getOrDefault("尚未更新")

@Composable
internal fun MonitorPanel(state: ScreenState, model: MarketViewModel) {
    val monitor = state.monitor
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsActive,null,Modifier.size(20.dp),tint=Ink)
            Spacer(Modifier.width(8.dp))
            Text("持续观察",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
            Switch(monitor.settings.enabled,model::toggleMonitoring,modifier=Modifier.testTag("monitor-toggle"))
        }
        Caption(if (monitor.settings.enabled) "应用内每 5 分钟更新 · 手机退到后台暂停" else "自动更新已暂停，仍可手动更新")
        Caption(if (state.demo) "演示数据 · 不是真实资金流或事件提醒" else monitor.note)
        if (monitor.settings.watched.isEmpty()) Caption("进入板块详情，点击「加入跟踪」持续观察资金变化。")
        else LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(monitor.settings.watched,key={it.id}) { board ->
                val series = monitor.flows[board.id]
                val summary = series?.let { summarizeFlows(it,monitor.settings.days) }
                val stale = series != null && !state.demo && series.dates.lastOrNull() != monitor.targetDate
                OutlinedButton(onClick={model.openBoard(board)},shape=RoundedCornerShape(10.dp)) {
                    Text(board.name,fontSize=12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (series?.error != null || stale) "待更新" else summary?.net?.let { money(it) } ?: "待接入",fontSize=11.sp,color=if (stale) Muted else color(summary?.net))
                }
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Caption("检查：${localTime(monitor.checkedAt)} · 北京时间") }
            TextButton(onClick=model::refreshIntelligence,enabled=!monitor.loading) { Text(if (monitor.loading) "检查中" else "立即更新",fontSize=12.sp) }
        }
    }
}

@Composable
internal fun FlowPanel(state: ScreenState, model: MarketViewModel) {
    val board = state.selected ?: return
    val monitor = state.monitor
    val series = monitor.flows[board.id]
    val days = monitor.settings.days
    val summary = series?.let { summarizeFlows(it,days) }
    val watched = monitor.settings.watched.any { it.id == board.id }
    Panel {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("多日资金跟踪",Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=17.sp)
            TextButton(onClick={model.toggleWatch(board)},modifier=Modifier.testTag("watch-board")) { Text(if (watched) "取消跟踪" else "加入跟踪") }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(5,10,20).forEach { n -> FilterChip(days==n,{model.setFlowDays(n)},label={Text("${n}日")},modifier=Modifier.testTag("flows-$n")) }
        }
        if (series == null || series.rows.isEmpty()) {
            Empty(series?.error ?: if (monitor.loading) "正在读取资金流" else "资金流待接入", "按同花顺板块标识匹配同一来源、同一口径的资金数据。")
            TextButton(onClick=model::refreshIntelligence,enabled=!monitor.loading) { Text("重新获取资金") }
        } else {
            series.error?.let { Text("$it · 下方为历史缓存",color=Rise,fontSize=12.sp) }
            val stale = !state.demo && series.dates.lastOrNull() != monitor.targetDate
            if (stale) Text(if (monitor.targetDate.isBlank()) "历史快照 · 目标交易日待确认，提醒暂停" else "历史快照 · 尚未取得目标交易日 ${monitor.targetDate} 的资金",color=Rise,fontSize=12.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Metric("${days}日累计净额",money(summary?.net))
                Metric("净流入日合计",quantity(summary?.positive,"元"))
                Metric("净流出日合计",quantity(summary?.negative,"元"))
            }
            Caption("覆盖 ${summary?.covered ?: 0} / $days 个交易日 · 缺日或更新失败时不计算累计值")
            val byDate = series.rows.associateBy { it.date }
            val dates = series.dates.takeLast(days)
            Canvas(Modifier.fillMaxWidth().height(105.dp).testTag("flows-chart")) {
                val maxValue = dates.mapNotNull { byDate[it]?.net?.let(::abs) }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                val half = size.height/2
                drawLine(Line,Offset(0f,half),Offset(size.width,half))
                val step = size.width/dates.size.coerceAtLeast(1)
                dates.forEachIndexed { i,date ->
                    val net = byDate[date]?.net
                    if (net == null) drawCircle(Muted,2.dp.toPx(),Offset((i+.5f)*step,half))
                    else {
                        val height = (abs(net)/maxValue*(half-5)).toFloat().coerceAtLeast(1f)
                        drawRect(color(net),Offset((i+.2f)*step,if (net>=0) half-height else half),Size(step*.6f,height))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Caption(dates.firstOrNull().orEmpty());Caption(dates.lastOrNull().orEmpty()) }
            if (!stale && series.error == null && (summary?.outflowStreak ?: 0) >= 3) {
                Text("资金提醒：连续 ${summary!!.outflowStreak} 个已观测交易日净流出",color=Rise,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
            }
            Caption("${series.source} · ${if (series.scope=="main") "主力口径" else "全口径"} · 元 · 更新 ${localTime(series.updatedAt)}")
            Caption("正值是净流入，负值是净流出；红柱／绿柱表示方向。上下两项累计是正／负净额分别求和，不是买卖总额。")
            var expanded by remember(board.id) { mutableStateOf(false) }
            TextButton(onClick={expanded=!expanded}) { Text(if (expanded) "收起逐日明细" else "查看逐日流入／流出") }
            if (expanded) {
                FlowTableRow("交易日","流入","流出","净额")
                dates.asReversed().forEach { date -> val row=byDate[date]; FlowTableRow(date.drop(5),quantity(row?.inflow),quantity(row?.outflow),money(row?.net)) }
                Caption("流入／流出为来源提供的总额，未提供时显示 —；不由净额反推。")
            }
        }
    }
}

@Composable
private fun FlowTableRow(a: String,b: String,c: String,d: String) {
    Row(Modifier.fillMaxWidth().padding(vertical=5.dp)) {
        listOf(a,b,c,d).forEach { Text(it,Modifier.weight(1f),fontSize=10.sp,color=Muted) }
    }
}

@Composable
internal fun EventPanel(market: MarketScope, state: ScreenState, model: MarketViewModel) {
    val feed = state.monitor.feed
    val now = Clock.System.now()
    val events = eventsFor(feed,market,now)
    val uri = LocalUriHandler.current
    val stale = feed.updatedAt.isNotEmpty() && runCatching { now.epochSeconds-Instant.parse(feed.updatedAt).epochSeconds > 86400 }.getOrDefault(true)
    Column(Modifier.fillMaxWidth().background(Color(0xFFF5F1E8),RoundedCornerShape(10.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsNone,null,Modifier.size(17.dp),tint=Ink)
            Spacer(Modifier.width(6.dp))
            Text("${market.title} · 重点事件",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        }
        if (feed.error != null) Caption("${feed.error} · 已有条目仅供查阅")
        if (stale) Caption("事件列表超过 24 小时未更新")
        if (events.isEmpty()) Caption(if (feed.updatedAt.isBlank()) "事件源待接入，连接后显示关联市场的重要事件。" else "当前无有效期内的重点事件；不代表没有市场风险。")
        var expanded by remember(market) { mutableStateOf(false) }
        events.take(if (expanded) events.size else 3).forEach { event ->
            val unread = event.revision !in state.monitor.settings.readEvents && feed.error == null && !stale
            Surface(color=Color.White,shape=RoundedCornerShape(8.dp)) {
                Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("${if (unread) "● " else ""}${if (event.importance=="high") "重点 · " else ""}${event.title}",fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                    Caption(event.summary)
                    Caption("${if(event.kind=="scheduled") "日程" else "事件"}：${localTime(event.occursAt)} 北京时间")
                    Caption("${event.source} · 发布 ${localTime(event.publishedAt)}")
                    Row {
                        var linkError by remember(event.url) { mutableStateOf(false) }
                        TextButton(onClick={runCatching { uri.openUri(event.url) }.onFailure { linkError=true }}) { Text(if(state.demo) "参考官网" else "查看来源",fontSize=11.sp) }
                        if (unread) TextButton(onClick={model.markEventRead(event)},modifier=Modifier.testTag("read-${event.id}")) { Text("标为已读",fontSize=11.sp) }
                        if (linkError) Text("链接打开失败",color=Rise,fontSize=11.sp)
                    }
                }
            }
        }
        if (events.size > 3) TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "收起事件" else "查看其余 ${events.size-3} 条") }
    }
}

@Composable
internal fun StrategyPicker(state: ScreenState, model: MarketViewModel) {
    var guide by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("技术组合",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            TextButton(onClick={guide=!guide}) { Text(if (guide) "收起依据" else "规则依据",fontSize=12.sp) }
        }
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(StrategyPreset.entries) { preset -> FilterChip(state.rules.preset==preset,{model.choosePreset(preset)},label={Text(preset.title)},modifier=Modifier.testTag("preset-${preset.name}")) }
        }
        Caption(state.rules.preset.summary)
        if (guide) {
            Caption("以 Nison 的蜡烛形态、Murphy 的趋势与量能、Edwards／Magee 的趋势结构为参考。先看趋势和关键位置，再看形态与成交量；量化阈值为本应用定义，并非书中的完整原公式。")
            Caption("顶部／底部形态需后续收盘确认。回调横盘只是观察候选；放量突破也可能失败。未作收益回测，不提供胜率或收益承诺。")
            listOf("蜡烛形态 · Nison" to "https://candlecharts.com/candlestick-patterns/", "趋势与量能 · Murphy" to "https://chartschool.stockcharts.com/table-of-contents/overview/john-murphys-10-laws-of-technical-trading", "趋势结构 · Edwards／Magee" to "https://cmtassociation.org/technically_speaking/technically-speaking-august-2021/").forEach { (title,url) ->
                TextButton(onClick={runCatching { uri.openUri(url) }}) { Text(title,fontSize=11.sp) }
            }
        }
    }
}

@Composable
internal fun MonitoringHighlights(state: ScreenState, model: MarketViewModel) {
    val alerts = reviewBoards(state).mapNotNull { board ->
        val series = state.monitor.flows[board.id] ?: return@mapNotNull null
        if (!flowAttention(series,state.monitor.targetDate,state.monitor.settings.days,state.demo).needsReview) return@mapNotNull null
        val summary = summarizeFlows(series,state.monitor.settings.days)
        if (summary.outflowStreak < 3) null else board to summary.outflowStreak
    }
    if (alerts.isNotEmpty()) Surface(color=Rise.copy(alpha=.07f),shape=RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(if(state.demo) "优先复核 · 资金变化演示" else "优先复核 · 资金变化",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            alerts.forEach { (board,count) -> TextButton(onClick={model.openBoard(board)}) { Text("${board.name} · 连续 $count 个交易日净流出",fontSize=12.sp,color=Rise) } }
        }
    }
}

@Composable
internal fun SetupExplanation(hit: SetupHit) {
    var expanded by remember(hit) { mutableStateOf(false) }
    Surface(color=if(hit.preset in listOf(StrategyPreset.TOP_RISK,StrategyPreset.DOWNTREND)) Rise.copy(alpha=.07f) else Fall.copy(alpha=.07f),shape=RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text("${hit.preset.title} · ${hit.status}",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            Text("失效条件：${hit.invalidation}",fontSize=12.sp,color=Ink)
            Caption("下一步确认：${hit.confirmation}")
            TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) { Text(if(expanded) "收起触发依据" else "查看 ${hit.evidence.size} 项触发依据",fontSize=11.sp) }
            if(expanded) hit.evidence.forEach { Caption("• $it") }
            Caption("计算日期 ${hit.date} · 结构观察，不代表价格预测")
        }
    }
}

@Composable
internal fun StrategyParametersEditor(p: StrategyParameters, update: (StrategyParameters) -> Unit) {
    Caption("组合独立使用下列参数，不叠加自定义 RSI／量比条件。所有阈值为本应用量化约定。")
    Text("趋势均线：MA${p.fast} / MA${p.slow}",fontSize=13.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(8 to 20,20 to 125).forEach { (fast,slow) -> FilterChip(p.fast==fast && p.slow==slow,{update(p.copy(fast=fast,slow=slow))},label={Text("$fast / $slow")}) }
    }
    ParamSlider("突破量比 ≥",p.breakoutVolume,1f..3f) { update(p.copy(breakoutVolume=it)) }
    ParamSlider("缩量上限 ≤",p.contractionVolume,.3f..1f) { update(p.copy(contractionVolume=it)) }
    ParamSlider("整理振幅上限 %",p.rangePct,3f..15f) { update(p.copy(rangePct=it)) }
    ParamSlider("突破缓冲 %",p.breakoutBufferPct,0f..2f) { update(p.copy(breakoutBufferPct=it)) }
    ParamSlider("距支撑／阻力 %",p.nearLevelPct,1f..5f) { update(p.copy(nearLevelPct=it)) }
    Text("整理窗口",fontSize=13.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(5,10,15).forEach { n -> FilterChip(p.baseDays==n,{update(p.copy(baseDays=n))},label={Text("$n 根")}) } }
    Text("突破回看",fontSize=13.sp)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(10,20,30).forEach { n -> FilterChip(p.lookback==n,{update(p.copy(lookback=n))},label={Text("$n 根")}) } }
    Caption("吞没判断实体包络；锤子／流星影线 ≥ ${decimal(p.shadowRatio,1)} 倍实体，需有先前趋势与关键位置。回调幅度 3%–15%，整理低点不低于慢均线的 98%。")
}

@Composable
private fun ParamSlider(label: String,value: Double,range: ClosedFloatingPointRange<Float>,update: (Double)->Unit) {
    Text("$label ${decimal(value,1)}",fontSize=13.sp)
    Slider(value.toFloat(),{update((it*10).roundToInt()/10.0)},valueRange=range)
}
