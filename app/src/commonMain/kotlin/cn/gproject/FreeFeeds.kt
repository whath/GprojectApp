package cn.gproject

import kotlinx.coroutines.CancellationException
import kotlinx.datetime.*
import kotlinx.serialization.json.*

data class MinuteFeed(val points: List<MinutePoint> = emptyList(), val date: String = "", val source: String = "", val error: String? = null)
data class StockMetrics(val date: String = "", val totalCap: Double? = null, val floatCap: Double? = null,
    val peTtm: Double? = null, val peStatic: Double? = null, val pb: Double? = null, val turnover: Double? = null,
    val turnoverDerived: Boolean = false, val source: String = "", val error: String? = null)
data class LimitUpItem(val symbol: String, val name: String, val days: Int)
data class LimitUpFeed(val date: String = "", val items: List<LimitUpItem> = emptyList(), val source: String = "")
data class DisclosureFeed(val date: String, val leaders: List<Leader>)
data class SeatRecord(val seat: String, val direction: String, val reason: String, val net: Double?)
data class SeatFeed(val date: String = "", val items: List<SeatRecord> = emptyList(), val error: String? = null)
data class AgendaItem(val title: String, val start: String, val end: String)
data class AgendaFeed(val items: List<AgendaItem> = emptyList(), val verifiedAt: String = "")

internal fun parseMinutes(data: JsonObject, id: String, date: String): MinuteFeed {
    require(data.str("instrument_id") == id && data.str("trade_date") == date) { "分钟行情标的或日期不一致" }
    require(data.str("timezone") == "Asia/Shanghai" && data.str("period") == "1m" && data.str("adjustment") == "none") { "分钟行情口径不支持" }
    require(data.str("source").isNotBlank()) { "分钟行情来源缺失" }
    val points = data.rows().map { row ->
        val stamp = row.str("timestamp")
        Instant.parse(stamp)
        require(stamp.startsWith(date + "T") && stamp.endsWith("+08:00")) { "分钟行情时间不一致" }
        val close = requireNotNull(row.num("close")) { "分钟价格缺失" }
        require(close > 0) { "分钟价格无效" }
        MinutePoint(stamp.substring(11, 16), close)
    }
    require(points.map { it.time }.distinct().size == points.size && points.zipWithNext().all { it.first.time < it.second.time }) { "分钟时间重复或逆序" }
    return MinuteFeed(points, date, data.str("source"))
}

internal fun parseMetrics(data: JsonObject, id: String, end: String): StockMetrics {
    require(data.str("instrument_id") == id && data.str("currency") == "CNY" && data.str("amount_unit") == "yuan") { "估值标的或金额单位不一致" }
    require(data.str("source").isNotBlank()) { "估值来源缺失" }
    val row = data.rows().lastOrNull() ?: return StockMetrics(error = "该日期暂无已采集估值")
    val date = row.str("trade_date").also { LocalDate.parse(it) }
    require(date <= end) { "估值日期超出查询范围" }
    val turnover = row.num("turnover_pct")
    val basis = row["turnover_basis"] as? JsonObject
    require(turnover == null || (turnover >= 0 && basis?.str("method") == "same_day_share_volume / dated_circulating_shares * 100")) { "换手率口径缺失" }
    for (key in listOf("total_market_cap", "float_market_cap")) require(row.num(key)?.let { it > 0 } != false) { "市值无效" }
    return StockMetrics(date, row.num("total_market_cap"), row.num("float_market_cap"), row.num("pe_ttm"),
        row.num("pe_static"), row.num("pb"), turnover, turnover != null, data.str("source"))
}

internal suspend fun MarketRepository.minuteFeed(id: String, date: String): MinuteFeed = try {
    parseMinutes(get("/v1/cn/stocks/$id/minutes", mapOf("trade_date" to date)), id, date)
} catch (e: CancellationException) { throw e }
catch (e: Exception) { MinuteFeed(error = if (e is ApiFailure) e.message else "分钟行情校验失败") }

internal suspend fun MarketRepository.metricsFeed(id: String, date: String): StockMetrics = try {
    parseMetrics(get("/v1/cn/stocks/$id/metrics", mapOf("end" to date, "limit" to "1")), id, date)
} catch (e: CancellationException) { throw e }
catch (e: Exception) { StockMetrics(error = if (e is ApiFailure) e.message else "估值行情校验失败") }

internal suspend fun MarketRepository.referenceIndices(): List<Quote> = get("/v1/indices").rows().map { row ->
    val id = row.str("id")
    require(row.str("unit") == "point" && id.contains(".index.")) { "指数点位口径不一致" }
    val date = row.str("trade_date")
    if (date.isNotBlank()) LocalDate.parse(date)
    val close = row.num("close")
    require(close == null || close > 0) { "指数点位无效" }
    Quote(id, row.str("name"), "指数点位", close, row.num("change_pct"), date.ifBlank { "未采集" }, row.str("source"))
}

internal suspend fun MarketRepository.usSectorQuotes(): List<Quote> = get("/v1/us/sectors").rows().map { row ->
    Quote("US.etf.${row.str("symbol")}", row.str("group"), row.str("symbol"), row.num("close"), row.num("change_1d_pct"), row.str("trade_date"), row.str("source"))
}

internal suspend fun MarketRepository.limitUpFeed(): LimitUpFeed {
    val data = get("/v1/cn/limit-up-ladder")
    require(data.str("basis") == "provider_reported") { "连板统计口径不支持" }
    val date = data.str("trade_date").also { LocalDate.parse(it) }
    val items = data.rows().map { row ->
        val days = requireNotNull(row.num("consecutive_days")) { "披露连板数缺失" }
        require(days >= 1 && days == days.toInt().toDouble()) { "披露连板数无效" }
        require(row.str("symbol").matches(Regex("[0-9]{6}"))) { "涨停标的代码无效" }
        LimitUpItem(row.str("symbol"), row.str("name"), days.toInt())
    }
    require(items.map { it.symbol }.distinct().size == items.size) { "涨停标的重复" }
    return LimitUpFeed(date, items, data.str("source"))
}

internal suspend fun MarketRepository.disclosures(): DisclosureFeed {
    val all = mutableListOf<Leader>()
    val keys = mutableSetOf<String>()
    var snapshot = ""
    var date = ""
    var total = -1
    var offset = 0
    while (true) {
        val response = get("/v1/cn/lhb", buildMap {
            put("limit", "500"); put("offset", offset.toString())
            if (offset == 0) put("refresh", "true")
            else { put("snapshot_id", snapshot); put("trade_date", date) }
        })
        val version = response.str("snapshot_id")
        val day = response.str("trade_date").also { LocalDate.parse(it) }
        val reported = (response["total"] as? JsonPrimitive)?.intOrNull ?: error("龙虎榜分页总数缺失")
        require(reported in 0..50000 && version.matches(Regex("[a-f0-9]{64}"))) { "龙虎榜分页元数据无效" }
        if (offset == 0) { snapshot = version; date = day; total = reported }
        require(version == snapshot && day == date && reported == total) { "龙虎榜版本变化，请刷新" }
        val page = response.rows()
        require(page.isNotEmpty() || offset == total) { "龙虎榜分页提前结束" }
        page.forEach { row ->
            require(keys.add(row.str("key").also { require(it.isNotBlank()) })) { "龙虎榜披露重复" }
            all += Leader(row.str("name"), row.str("symbol"), row.num("net_amount"), row.str("reason"), date)
        }
        offset += page.size
        require(offset <= total) { "龙虎榜分页超过总数" }
        if (offset == total) return DisclosureFeed(date, all)
    }
}

internal suspend fun MarketRepository.seatFeed(symbol: String, date: String): SeatFeed = try {
    val all = mutableListOf<SeatRecord>()
    for (direction in listOf("买入", "卖出")) {
        val data = get("/v1/cn/lhb/$symbol/seats", mapOf("trade_date" to date, "direction" to direction))
        require(data.str("trade_date") == date) { "席位披露日期不一致" }
        data.rows().forEach { row ->
            require(row.str("symbol") == symbol && row.str("direction") == direction) { "席位披露标的或方向不一致" }
            all += SeatRecord(row.str("seat"), direction, row.str("reason"), row.num("net_amount"))
        }
    }
    SeatFeed(date, all)
} catch (e: CancellationException) { throw e }
catch (e: Exception) { SeatFeed(date = date, error = if (e is ApiFailure) e.message else "席位披露校验失败") }

internal suspend fun MarketRepository.officialAgenda(): AgendaFeed {
    val today = Clock.System.now().toLocalDateTime(TimeZone.of("America/New_York")).date
    val data = get("/v1/scheduled-events", mapOf("start" to today.toString(), "end" to today.plus(DatePeriod(months = 6)).toString()))
    val items = data.rows().map { row ->
        require(row.str("time_precision") == "date" && row.str("source") == "official/federalreserve/fomc-calendar") { "日历来源或时间精度不支持" }
        val start = row.str("start_date").also { LocalDate.parse(it) }
        val end = row.str("end_date").also { LocalDate.parse(it) }
        require(start <= end && row.str("presence") == "present_in_latest_source") { "日历记录无效" }
        AgendaItem(row.str("title"), start, end)
    }
    return AgendaFeed(items, data.str("verified_at"))
}
