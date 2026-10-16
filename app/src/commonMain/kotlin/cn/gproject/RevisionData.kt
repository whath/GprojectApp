package cn.gproject

import kotlinx.datetime.*
import kotlinx.serialization.json.*

data class DailyAsset(val id: String, val name: String, val symbol: String, val close: Double? = null,
    val change: Double? = null, val date: String = "", val source: String = "", val rank: Int = 0, val periodSessions: Int = 5)
data class DailyRanking(val items: List<DailyAsset> = emptyList(), val date: String = "", val eligible: Int = 0,
    val expected: Int = 0, val basis: String = "", val snapshot: String = "")
data class DailyFund(val id: String, val name: String, val net: Double, val date: String)
data class MacroQuote(val id: String, val name: String, val value: Double, val unit: String, val change: Double?,
    val changeUnit: String, val date: String, val trend: List<Double>, val stale: Boolean, val age: Int)
data class CalendarEntry(val id: String, val title: String, val time: String, val region: String, val country: String,
    val importance: Int, val unit: String, val actual: String?, val expected: String?, val previous: String?, val revised: String?)
data class DailyCalendar(val items: List<CalendarEntry>, val stale: Boolean, val covered: Boolean, val collectedAt: String)
data class PolicyEntry(val id: String, val title: String, val publisher: String, val date: String, val url: String, val category: String)
data class DailyOverview(val cn: String = "", val us: String = "", val boards: String = "", val lhb: String = "",
    val members: String = "", val targets: Map<String,String> = emptyMap(), val features: Set<String> = emptySet(),
    val detailFeatures: Map<String,Set<String>> = emptyMap(), val stockCount: Int = 0)
data class BoardMember(val id: String, val name: String, val symbol: String, val available: Boolean)
data class DailyDetail(val asset: DailyAsset, val candles: List<Candle> = emptyList(), val loading: Boolean = true,
    val error: String? = null, val complete: Boolean = false, val funds: List<Pair<String,Double>> = emptyList(),
    val members: List<BoardMember> = emptyList(), val metrics: StockMetrics? = null, val currency: String = "CNY")

internal fun JsonObject.intValue(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: error("服务数据缺少 $key")
internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
internal fun checkedDate(value: String): String = value.also { LocalDate.parse(it) }
internal fun checkedAssetId(value: String): String = value.also {
    require(it.matches(Regex("(?:CN|US)\\.(?:stock|etf|industry|concept)\\.[A-Za-z0-9_\\.-]+"))) { "标的标识无效" }
    if(it.startsWith("CN.stock.") || it.startsWith("CN.etf.")) require(it.substringAfterLast('.').matches(Regex("[0-9]{6}")))
    if(it.startsWith("CN.industry.") || it.startsWith("CN.concept.")) require(it.substringAfterLast('.').matches(Regex("BK[0-9]{4}")))
}

internal suspend fun MarketRepository.dailyPages(path: String, params: Map<String,String>): JsonObject {
    var offset=0; var total=-1; var snapshot=""; var first: JsonObject?=null
    val rows=mutableListOf<JsonObject>()
    while(true) {
        val page=get(path,params.filterKeys { it !in setOf("limit","offset","refresh","snapshot_id") }+buildMap {
            put("limit","500");put("offset",offset.toString())
            if(offset==0) put("refresh","true") else put("snapshot_id",snapshot)
        })
        val version=page.str("snapshot_id");val count=page.intValue("total")
        require(version.matches(Regex("[a-f0-9]{64}")) && count in 0..50000) { "列表版本缺失" }
        if(first==null) { first=page; snapshot=version;total=count }
        require(version==snapshot && count==total) { "列表版本变化，请刷新" }
        val part=page.rows()
        require(part.isNotEmpty() || offset==total) { "列表提前结束" }
        rows+=part;offset+=part.size
        require(offset<=total) { "列表超出披露总数" }
        if(offset==total) break
    }
    return JsonObject(first!!+mapOf("items" to JsonArray(rows)))
}
internal fun parseDailyRanking(data: JsonObject): DailyRanking {
    require(data.str("period") == "5d" && data.intValue("period_sessions") == 5) { "涨幅窗口不一致" }
    val day = checkedDate(data.str("trade_date"))
    val eligible = data.intValue("eligible_count"); val expected = data.intValue("expected_count")
    require(eligible in 0..expected && expected <= 50000) { "涨幅覆盖数量无效" }
    val items = data.rows().map { row ->
        require(row.str("trade_date") == day && row.str("source").isNotBlank()) { "涨幅日期或来源缺失" }
        val id = checkedAssetId(row.str("id"))
        val isBoard = id.contains(".industry.") || id.contains(".concept.")
        require(row.str("adjustment") == if (isBoard) "none" else "qfq") { "涨幅复权口径不一致" }
        DailyAsset(id, row.str("name"), row.str("symbol"), requireNotNull(row.num("close")).also { require(it > 0) },
            requireNotNull(row.num("change_5d_pct")), day, row.str("source"), row.intValue("rank"))
    }
    require(items.map { it.id }.distinct().size == items.size && items.size <= eligible) { "涨幅记录重复或超出覆盖" }
    return DailyRanking(items, day, eligible, expected, data.str("return_basis"), data.str("snapshot_id"))
}

internal suspend fun MarketRepository.dailyOverview(): DailyOverview {
    val data = get("/v1/data-overview")
    require(data.str("api_version").startsWith("0.6.") && data.intValue("history_sessions") == 30) { "需要连接支持近 30 日数据的新版服务" }
    val dates = data["latest_dates"]!!.jsonObject
    fun day(key: String) = dates.str(key).also { if (it.isNotBlank()) checkedDate(it) }
    return DailyOverview(day("cn"), day("us"), day("boards"), day("lhb"), day("membership_observed"),
        data["calendar_targets"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        data["features"]!!.jsonObject.filterValues { it.jsonPrimitive.booleanOrNull == true }.keys,
        data["detail_features"]?.jsonObject?.mapValues { (_,v) -> v.jsonArray.map { it.jsonPrimitive.content }.toSet() }.orEmpty(),
        data.intValue("cn_stocks_with_history"))
}

internal suspend fun MarketRepository.dailyRanking(kind: String, day: String): DailyRanking = parseDailyRanking(dailyPages("/v1/cn/rankings",
    mapOf("kind" to kind, "period" to "5d", "trade_date" to day, "classification_source" to "eastmoney", "limit" to "100", "refresh" to "true")))

internal suspend fun MarketRepository.dailyFunds(kind: String, day: String): List<DailyFund> {
    val data = dailyPages("/v1/cn/fund-rankings", mapOf("kind" to kind, "period" to "5d", "trade_date" to day))
    require(data.str("classification_source") == "eastmoney" && data.str("trade_date") == day && data.intValue("period_sessions") == 5)
    return data.rows().map { row ->
        require(row.str("unit") == "yuan" && row.str("currency") == "CNY") { "资金单位不一致" }
        DailyFund(checkedAssetId(row.str("id")), row.str("name"), requireNotNull(row.num("net")), day)
    }
}

internal suspend fun MarketRepository.dailyMacro(): List<MacroQuote> = get("/v1/macro/assets").rows().map { row ->
    val id = row.str("asset_id"); val unit = row.str("unit"); val changeUnit = row.str("change_unit")
    require(id in setOf("gold","brent","us2y","us10y","us30y")) { "宏观品种不支持" }
    require(unit == when(id) { "gold" -> "USD/troy_ounce"; "brent" -> "USD/barrel"; else -> "percent" }) { "宏观单位不一致" }
    require(changeUnit == if (id.startsWith("us")) "basis_points" else "percent") { "宏观变化单位不一致" }
    MacroQuote(id, row.str("name"), requireNotNull(row.num("value")), unit, row.num("change"), changeUnit,
        checkedDate(row.str("data_date")), (row["trend"] as? JsonArray).orEmpty().map { requireNotNull(it.jsonPrimitive.doubleOrNull).also { value -> require(value.isFinite()) } },
        row.flag("fetch_stale") || row.str("last_attempt_status") == "failed", row.intValue("source_age_days"))
}

internal suspend fun MarketRepository.dailyCalendar(): DailyCalendar {
    val today = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date
    val data=get("/v1/economic-calendar", mapOf("start" to today.toString(), "end" to today.plus(DatePeriod(days=7)).toString(), "limit" to "1000"))
    val items=data.rows().map { row ->
        require(row.str("source") == "wallstreetcn/macro-calendar/v1" && row["published_at"] == JsonNull) { "经济日历来源不一致" }
        val stamp = row.str("occurs_at").also { Instant.parse(it) }
        fun value(key: String) = row.str(key + "_raw").ifBlank { null }
        CalendarEntry(row.str("id"), row.str("title"), stamp, row.str("region"), row.str("country_code"), row.intValue("importance"),
            row.str("unit"), value("actual"), value("expected"), value("previous"), value("revised"))
    }
    return DailyCalendar(items,data.flag("fetch_stale") || data.str("last_attempt_status")=="failed",data.flag("window_covered"),data.str("collected_at"))
}

internal suspend fun MarketRepository.dailyPolicy(): List<PolicyEntry> = get("/v1/policy-news", mapOf("limit" to "300")).rows().map { row ->
    val url = row.str("original_url")
    require(url.matches(Regex("https://(?:www\\.)?(?:gov\\.cn|pbc\\.gov\\.cn|csrc\\.gov\\.cn|ndrc\\.gov\\.cn|mofcom\\.gov\\.cn)/[^\\s]+"))) { "政策原文地址无效" }
    require(row.str("time_precision") == "date" && row["published_at"] == JsonNull) { "政策时间精度不一致" }
    PolicyEntry(row.str("id"), row.str("title"), row.str("publisher"), checkedDate(row.str("published_date")), url, row.str("category"))
}

internal suspend fun MarketRepository.dailyDetail(asset: DailyAsset, overview: DailyOverview): DailyDetail {
    val day = asset.date.ifBlank { if (asset.id.startsWith("US.")) overview.us else overview.cn }
    val data = get("/v1/bars/${asset.id}", mapOf("end" to day, "limit" to "30"))
    require(data.str("adjustment") == "none" && data.intValue("window_sessions") == 30)
    require(data["instrument"]!!.jsonObject.str("id") == asset.id)
    val rows = data.rows().map { row ->
        val open = requireNotNull(row.num("open")); val high = requireNotNull(row.num("high")); val low = requireNotNull(row.num("low")); val close = requireNotNull(row.num("close"))
        require(low > 0 && high >= maxOf(open, close) && low <= minOf(open, close)) { "日线 OHLC 无效" }
        Candle(checkedDate(row.str("trade_date")), open, high, low, close, row.num("volume"), row.num("amount"),
            row.str("source").also { require(it.isNotBlank()) }, row.str("volume_unit"), row.num("change_pct"))
    }
    require(rows.size <= 30 && rows.zipWithNext().all { it.first.date < it.second.date }) { "日线日期或窗口异常" }
    require(rows.map { it.source }.distinct().size <= 1) { "日线来源不一致" }
    val currency = data["instrument"]!!.jsonObject.str("currency")
    return DailyDetail(asset, rows, false, complete=(data["history_coverage"] as? JsonObject)?.flag("complete") == true, currency=currency)
}

internal suspend fun MarketRepository.dailyMembers(id: String, observed: String): List<BoardMember> {
    val result = mutableListOf<BoardMember>(); var offset = 0; var snapshot = ""
    while (true) {
        val data = get("/v1/cn/boards/$id/members", buildMap {
            put("observed_date", observed); put("limit", "500"); put("offset", offset.toString())
            if (offset == 0) put("refresh", "true") else put("snapshot_id", snapshot)
        })
        val version = data.str("snapshot_id")
        require(version.matches(Regex("[a-f0-9]{64}")) && (offset == 0 || snapshot == version)) { "成分版本变化" }
        snapshot = version
        val rows = data.rows()
        result += rows.map { row -> BoardMember("CN.stock.${row.str("symbol")}", row.str("name"), row.str("symbol"), row.flag("has_history")) }
        if (rows.size < 500) break
        offset += rows.size; require(offset <= 10000) { "成分数量超出范围" }
    }
    require(result.map { it.id }.distinct().size == result.size) { "成分重复" }
    return result
}
