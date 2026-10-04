package cn.gproject

import kotlinx.coroutines.CancellationException
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import kotlin.math.*

data class StockFundDay(val date: String, val inflow: Double?, val outflow: Double?, val net: Double)
data class StockFundSeries(
    val instrumentId: String,
    val rows: List<StockFundDay>,
    val source: String,
    val currency: String,
    val updatedAt: String,
    val error: String? = null,
)

private val cnStockId = Regex("CN\\.stock\\.[0-9]{6}")

/** The optional endpoint supplies stock-specific main-order flows in yuan, never board flows. */
internal fun parseStockFunds(json: JsonObject, instrumentId: String, end: String): StockFundSeries {
    require(cnStockId.matches(instrumentId) && json.str("instrument_id") == instrumentId) { "个股资金标识不一致" }
    val endDate = LocalDate.parse(end)
    require(json.str("currency") == "CNY" && json.str("unit") == "yuan") { "个股资金单位须为人民币元" }
    require(json.str("scope") == "main") { "个股资金须为主力统计口径" }
    val source = json.str("source").also { require(it.isNotBlank()) { "个股资金来源缺失" } }
    val updated = json.str("updated_at").also { Instant.parse(it) }
    val items = json["items"] as? JsonArray ?: error("个股资金记录缺失")
    require(items.size <= 2000) { "个股资金记录超过请求窗口" }
    fun JsonObject.money(key: String): Double? {
        val value = this[key]
        if (value == null || value == JsonNull) return null
        return requireNotNull((value as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }) { "个股资金金额无效" }
    }
    val rows = items.map { item ->
        val row = item as? JsonObject ?: error("个股资金记录格式异常")
        val date = row.str("trade_date")
        require(LocalDate.parse(date) <= endDate) { "个股资金日期晚于行情截至日期" }
        require(row["source"] == null || row.str("source") == source) { "个股资金来源不一致" }
        require(row["instrument_id"] == null || row.str("instrument_id") == instrumentId) { "个股资金记录标识不一致" }
        val net = requireNotNull(row.money("net")) { "个股主力净流入缺失" }
        val inflow = row.money("inflow")
        val outflow = row.money("outflow")
        require(inflow?.let { it >= 0 } != false && outflow?.let { it >= 0 } != false) { "个股资金流入流出总额不能为负" }
        if (inflow != null && outflow != null) {
            require(abs(inflow - outflow - net) <= max(1.0, abs(net) * .0001)) { "个股资金净额与流入流出不一致" }
        }
        StockFundDay(date, inflow, outflow, net)
    }
    require(rows.map { it.date }.distinct().size == rows.size) { "个股资金日期重复" }
    return StockFundSeries(instrumentId, rows.sortedBy { it.date }, source, "CNY", updated)
}

internal suspend fun MarketRepository.stockFunds(instrumentId: String, end: String): StockFundSeries {
    fun unavailable(message: String) = StockFundSeries(instrumentId, emptyList(), "", if (instrumentId.startsWith("US.")) "USD" else "CNY", "", message)
    if (!cnStockId.matches(instrumentId)) return unavailable("当前仅支持 A 股个股主力资金")
    if (runCatching { LocalDate.parse(end) }.isFailure) return unavailable("日线截至日期无效，主力资金未请求")
    return try {
        parseStockFunds(get("/v1/cn/stocks/$instrumentId/flows", mapOf("end" to end, "limit" to "2000")), instrumentId, end)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiFailure) {
        unavailable(if (e.status == 404 || e.status == 501) "个股主力资金接口待接入" else e.message ?: "个股主力资金读取失败")
    } catch (_: IllegalArgumentException) {
        unavailable("个股主力资金数据校验失败")
    } catch (_: IllegalStateException) {
        unavailable("个股主力资金数据校验失败")
    } catch (_: Exception) {
        unavailable("个股主力资金暂不可用，请稍后重试")
    }
}

/** Align with displayed candles; a week/month needs every observed daily candle, not calendar-day fills. */
internal fun alignedStockFunds(
    dailyBars: List<Candle>, displayBars: List<Candle>, period: ChartPeriod, funds: StockFundSeries?,
): List<Double?> {
    fun unavailable() = List<Double?>(displayBars.size) { null }
    if (funds == null || period == ChartPeriod.INTRADAY || dailyBars.isEmpty()) return unavailable()
    if (dailyBars.map { it.date }.distinct().size != dailyBars.size || funds.rows.map { it.date }.distinct().size != funds.rows.size) return unavailable()
    fun groupKey(date: String): String {
        val parsed = LocalDate.parse(date)
        return when (period) {
            ChartPeriod.WEEK -> (parsed.toEpochDays() - parsed.dayOfWeek.isoDayNumber + 1).toString()
            ChartPeriod.MONTH -> date.take(7)
            else -> date
        }
    }
    return runCatching {
        val flows = funds.rows.associateBy { it.date }
        val dailyGroups = dailyBars.sortedBy { it.date }.groupBy { groupKey(it.date) }
        displayBars.map { bar ->
            val group = dailyGroups[groupKey(bar.date)].orEmpty()
            if (group.isEmpty() || group.last().date != bar.date) null
            else {
                val values = group.map { flows[it.date]?.net?.takeIf(Double::isFinite) }
                if (values.any { it == null }) null else values.sumOf { it!! }.takeIf(Double::isFinite)
            }
        }
    }.getOrElse { unavailable() }
}

internal fun demoStockFunds(quote: Quote, candles: List<Candle>): StockFundSeries {
    if (!quote.symbol.matches(Regex("[0-9]{6}"))) return StockFundSeries(quote.id, emptyList(), "", "USD", "", "当前仅支持 A 股个股主力资金")
    val seed = quote.symbol.fold(0) { total, ch -> (total + ch.code) % 100 }
    return StockFundSeries(quote.id, candles.mapIndexed { index, bar ->
        // Independent illustrative values; do not infer order flow from price or traded volume.
        val net = sin(index * .47 + seed) * 48000000.0 + cos(index * .13) * 12000000.0
        val inflow = 110000000.0 + sin(index * .29 + seed) * 18000000.0
        StockFundDay(bar.date, inflow, inflow - net, net)
    }, "模拟个股主力资金", "CNY", Clock.System.now().toString())
}
