package cn.gproject

import kotlinx.datetime.*
import kotlinx.serialization.json.*
import kotlin.math.*

enum class MarketScope(val title: String) { US("美股"), JP("日本市场"), KR("韩国市场"), OIL("原油"), GOLD("黄金") }
data class FlowDay(val date: String, val inflow: Double?, val outflow: Double?, val net: Double)
data class FlowSeries(
    val boardId: String, val dates: List<String>, val rows: List<FlowDay>,
    val source: String, val scope: String, val updatedAt: String,
    val error: String? = null,
)
data class FlowSummary(val covered: Int, val expected: Int, val net: Double?, val positive: Double?, val negative: Double?, val outflowStreak: Int)

internal fun summarizeFlows(series: FlowSeries, days: Int): FlowSummary {
    val dates = series.dates.takeLast(days)
    val byDate = series.rows.associateBy { it.date }
    val selected = dates.mapNotNull(byDate::get)
    val complete = dates.size == days && selected.size == days && series.error == null
    val streak = if (series.error != null) 0 else dates.asReversed().takeWhile { byDate[it]?.net?.let { n -> n < 0 } == true }.size
    return FlowSummary(selected.size, days, if (complete) selected.sumOf { it.net } else null,
        if (complete) selected.sumOf { max(it.net,0.0) } else null,
        if (complete) selected.sumOf { max(-it.net,0.0) } else null,streak)
}

data class MarketEvent(
    val id: String, val title: String, val summary: String, val markets: Set<MarketScope>,
    val importance: String, val kind: String, val occursAt: String, val publishedAt: String,
    val expiresAt: String, val updatedAt: String, val source: String, val url: String,
) { val revision get() = "$id|$updatedAt" }

data class EventFeed(val events: List<MarketEvent> = emptyList(), val updatedAt: String = "", val error: String? = null)
data class MonitorSettings(val enabled: Boolean = true, val days: Int = 5, val watched: List<Board> = emptyList(), val readEvents: Set<String> = emptySet())
data class MonitorState(
    val settings: MonitorSettings = MonitorSettings(), val flows: Map<String,FlowSeries> = emptyMap(),
    val feed: EventFeed = EventFeed(), val loading: Boolean = false, val checkedAt: String = "", val note: String = "连接后开始监控",
    val targetDate: String = "",
)

internal fun eventsFor(feed: EventFeed, market: MarketScope, now: Instant): List<MarketEvent> = feed.events
    .filter { market in it.markets && Instant.parse(it.publishedAt) <= now && Instant.parse(it.expiresAt) > now }
    .sortedWith(compareByDescending<MarketEvent> { it.importance == "high" }.thenBy { it.occursAt })

internal fun JsonObject.stringArray(key: String) = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
internal fun parseFlows(json: JsonObject, boardId: String): FlowSeries {
    require(json.str("board_id") == boardId && json.str("classification_source") == "ths") { "板块分类或标识不一致" }
    require(json.str("currency") == "CNY" && json.str("unit") == "yuan") { "资金单位须为人民币元" }
    require(json.str("scope") in setOf("main","all")) { "资金统计口径缺失" }
    val dates = json.stringArray("trade_dates")
    require(dates.isNotEmpty() && dates.size <= 20 && dates.distinct().size == dates.size && dates.sorted() == dates) { "交易日历缺失或重复" }
    dates.forEach { LocalDate.parse(it) }
    val source = json.str("source").also { require(it.isNotBlank()) { "资金来源缺失" } }
    val updated = json.str("updated_at").also { Instant.parse(it) }
    val rows = json.rows().map { row ->
        require(row.str("trade_date") in dates) { "资金日期不在交易日历内" }
        val net = requireNotNull(row.num("net")) { "净额缺失" }
        val inflow = row.num("inflow"); val outflow = row.num("outflow")
        require(inflow?.let { it >= 0 } != false && outflow?.let { it >= 0 } != false) { "流入流出总额不能为负" }
        if (inflow != null && outflow != null) require(abs(inflow-outflow-net) <= max(1.0,abs(net)*.0001)) { "净额与流入流出不一致" }
        FlowDay(row.str("trade_date"),inflow,outflow,net)
    }
    require(rows.map { it.date }.distinct().size == rows.size) { "资金日期重复" }
    return FlowSeries(boardId,dates,rows.sortedBy { it.date },source,json.str("scope"),updated)
}

internal fun parseEvents(json: JsonObject): EventFeed {
    val updated = json.str("updated_at").also { Instant.parse(it) }
    val events = json.rows().map { row ->
        val id = row.str("id"); val title = row.str("title"); val url = row.str("url")
        require(id.isNotBlank() && title.isNotBlank() && row.str("source").isNotBlank()) { "事件信息不完整" }
        require(url.startsWith("https://") && !url.contains(' ') && !url.contains('\n')) { "事件来源须为 HTTPS" }
        val scopes = row.stringArray("markets").map { MarketScope.valueOf(it) }.toSet()
        require(scopes.isNotEmpty()) { "事件未指定市场" }
        val occurrence = row.str("occurs_at").also { Instant.parse(it) }
        val publication = row.str("published_at").also { Instant.parse(it) }
        val expires = row.str("expires_at").also { Instant.parse(it) }
        val revision = row.str("updated_at").also { Instant.parse(it) }
        require(Instant.parse(expires) > Instant.parse(publication)) { "事件有效期异常" }
        require(row.str("importance") in setOf("high","normal") && row.str("kind") in setOf("scheduled","news")) { "事件类别异常" }
        MarketEvent(id,title,row.str("summary"),scopes,row.str("importance"),row.str("kind"),occurrence,publication,expires,revision,row.str("source"),url)
    }.groupBy { it.id }.map { (_, revisions) -> revisions.maxBy { Instant.parse(it.updatedAt) } }
    return EventFeed(events,updated)
}

internal suspend fun MarketRepository.flows(boardId: String): FlowSeries = try {
    parseFlows(get("/v1/cn/boards/$boardId/flows",mapOf("limit" to "20","classification_source" to "ths")),boardId)
} catch (e: ApiFailure) {
    if (e.status == 404 || e.status == 501) error("资金流接口待接入") else throw e
}

internal suspend fun MarketRepository.events(): EventFeed = try {
    parseEvents(get("/v1/events",mapOf("limit" to "100")))
} catch (e: ApiFailure) {
    if (e.status == 404 || e.status == 501) error("热点事件接口待接入") else throw e
}

internal expect fun readMonitorPreferences(): String?
internal expect fun writeMonitorPreferences(value: String)

internal fun loadMonitorSettings(): MonitorSettings = runCatching {
    val root = Json.parseToJsonElement(readMonitorPreferences() ?: "{}").jsonObject
    MonitorSettings(root["enabled"]?.jsonPrimitive?.booleanOrNull ?: true, root.num("days")?.toInt()?.takeIf { it in listOf(5,10,20) } ?: 5,
        (root["watched"] as? JsonArray)?.map { val b=it.jsonObject; Board(b.str("id"),b.str("name"),null) }?.filter { it.id.startsWith("CN.industry.THS_") }?.take(12).orEmpty(),root.stringArray("read").takeLast(500).toSet())
}.getOrDefault(MonitorSettings())

internal fun saveMonitorSettings(settings: MonitorSettings) {
    val json = buildJsonObject {
        put("enabled",settings.enabled); put("days",settings.days)
        putJsonArray("watched") { settings.watched.filter { !it.id.startsWith("demo") }.forEach { b -> add(buildJsonObject { put("id",b.id);put("name",b.name) }) } }
        putJsonArray("read") { settings.readEvents.toList().takeLast(500).forEach { add(JsonPrimitive(it)) } }
    }
    writeMonitorPreferences(json.toString())
}

internal fun demoFlows(id: String): FlowSeries {
    val dates = demoStock(Quote("demo","模拟","688981")).candles.takeLast(20).map { it.date }
    val offset = id.last().digitToIntOrNull() ?: 1
    return FlowSeries(id,dates,dates.mapIndexed { i,date ->
        val net = if (i >= 17 && offset % 2 == 0) -(i-16)*80000000.0 else sin(i*.6+offset)*180000000
        val inflow = 900000000.0 + i*12000000
        FlowDay(date,inflow,inflow-net,net)
    },"模拟资金流","main",Clock.System.now().toString())
}

internal fun demoEvents(now: Instant = Clock.System.now()): EventFeed {
    val data = listOf(
        Triple(setOf(MarketScope.US,MarketScope.GOLD),"美联储政策会议关注","关注利率路径及美元变化；此条为演示事件，不是会议日程"),
        Triple(setOf(MarketScope.OIL),"原油库存报告关注","关注库存、炼厂开工与供应变化；此条为演示事件"),
        Triple(setOf(MarketScope.JP),"日本央行政策沟通关注","关注政策声明与日元波动；此条为演示事件"),
        Triple(setOf(MarketScope.KR),"韩国出口与半导体数据关注","关注出口景气及韩元变化；此条为演示事件"),
    )
    val urls = listOf("https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm","https://www.eia.gov/petroleum/supply/weekly/","https://www.boj.or.jp/en/mopo/mpmsche_minu/index.htm","https://www.bok.or.kr/eng/main/main.do")
    return EventFeed(data.mapIndexed { i,(markets,title,summary) ->
        MarketEvent("demo-event-$i",title,summary,markets,"high","scheduled",Instant.fromEpochSeconds(now.epochSeconds+86400).toString(),
            Instant.fromEpochSeconds(now.epochSeconds-3600).toString(),Instant.fromEpochSeconds(now.epochSeconds+172800).toString(),"2026-01-01T00:00:00Z","模拟提醒 · 官网供查阅",urls[i])
    },now.toString())
}
