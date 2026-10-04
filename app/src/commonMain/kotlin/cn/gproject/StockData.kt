package cn.gproject

import kotlinx.datetime.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.*

enum class ChartPeriod(val label: String) { INTRADAY("分时"), DAY("日K"), WEEK("周K"), MONTH("月K") }

data class Candle(
    val date: String, val open: Double, val high: Double, val low: Double, val close: Double,
    val volume: Double?, val amount: Double?, val source: String,
    val volumeUnit: String = "", val change: Double? = null,
)

data class MinutePoint(val time: String, val price: Double)

data class StockDetail(
    val quote: Quote,
    val candles: List<Candle> = emptyList(),
    val minutes: List<MinutePoint> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val leader: Leader? = null,
    val currency: String = "CNY",
    val funds: StockFundSeries? = null,
)

internal fun StockDetail.snapshotQuote(): Quote {
    val last = candles.lastOrNull() ?: return quote
    return quote.copy(price = last.close, change = last.change, date = last.date, source = last.source, trend = candles.takeLast(40).map { it.close })
}

/** Calendar weeks start on Monday; no synthetic sessions or missing-volume zero fills. */
internal fun aggregateCandles(bars: List<Candle>, period: ChartPeriod): List<Candle> {
    if (period == ChartPeriod.INTRADAY) return emptyList()
    val sorted = bars.sortedBy { it.date }
    require(sorted.map { it.date }.distinct().size == sorted.size) { "日线日期重复" }
    require(sorted.map { it.source }.distinct().size <= 1) { "历史行情包含不同来源，不能合并" }
    if (period == ChartPeriod.DAY) return sorted
    return sorted.groupBy { bar ->
        val date = LocalDate.parse(bar.date)
        if (period == ChartPeriod.MONTH) bar.date.take(7)
        else (date.toEpochDays() - (date.dayOfWeek.isoDayNumber - 1)).toString()
    }.values.map { group ->
        val first = group.first()
        val last = group.last()
        first.copy(
            date = last.date, high = group.maxOf { it.high }, low = group.minOf { it.low }, close = last.close,
            volume = if (group.all { it.volume != null && it.volumeUnit == first.volumeUnit }) group.sumOf { it.volume!! } else null,
            amount = if (group.all { it.amount != null }) group.sumOf { it.amount!! } else null,
            change = null,
        )
    }
}

internal suspend fun MarketRepository.stockDetail(quote: Quote, end: String, leader: Leader?): StockDetail = coroutineScope {
    // This optional series is independent of OHLCV: an unavailable endpoint must not erase the chart.
    val funds = async { stockFunds(quote.id, end) }
    val response = get("/v1/bars/${quote.id}", buildMap {
        put("limit", "2000")
        if (runCatching { LocalDate.parse(end) }.isSuccess) put("end", end)
    })
    require(response.str("adjustment") == "none") { "不支持的复权口径" }
    val rows = response.rows().map { r ->
        val date = r.str("trade_date")
        LocalDate.parse(date)
        val open = requireNotNull(r.num("open")) { "开盘价缺失" }
        val high = requireNotNull(r.num("high")) { "最高价缺失" }
        val low = requireNotNull(r.num("low")) { "最低价缺失" }
        val close = requireNotNull(r.num("close")) { "收盘价缺失" }
        require(low > 0 && high >= max(open, close) && low <= min(open, close)) { "OHLC 数据不完整" }
        require(r.num("volume")?.let { it >= 0 } != false && r.num("amount")?.let { it >= 0 } != false) { "成交数据异常" }
        require(r.str("source").isNotBlank()) { "行情来源缺失" }
        Candle(date, open, high, low, close, r.num("volume"), r.num("amount"), r.str("source"), r.str("volume_unit"), r.num("change_pct"))
    }
    val candles = aggregateCandles(rows, ChartPeriod.DAY)
    val instrument = response["instrument"] as? kotlinx.serialization.json.JsonObject
    StockDetail(quote, candles, loading = false, leader = leader, currency = instrument?.str("currency").orEmpty().ifBlank { if (quote.id.startsWith("US.")) "USD" else "CNY" }, funds = funds.await())
}

internal fun demoStock(quote: Quote, leader: Leader? = null): StockDetail {
    val seed = quote.symbol.fold(0) { total, ch -> (total + ch.code) % 100 }
    var date = LocalDate(2025, 6, 2)
    var previous = 24.0 + seed
    val generated = List(280) { i ->
        while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) date = LocalDate.fromEpochDays(date.toEpochDays() + 1)
        val open = previous * (1 + sin(i * .8 + seed) * .009)
        val close = open * (1 + sin(i * .43 + seed) * .023 + .001)
        val volume = 280000.0 + (sin(i * .68) + 1) * 190000
        Candle(date.toString(), open, max(open, close) * 1.012, min(open, close) * .986, close, volume,
            volume * 100 * (open + close) / 2, "模拟行情", "lot_100_shares", (close / previous - 1) * 100).also {
            previous = close
            date = LocalDate.fromEpochDays(date.toEpochDays() + 1)
        }
    }
    val scenario = listOf("688981","300308","300750","600036","300059").indexOf(quote.symbol)
    val candles = if (scenario < 0) generated else demoScenario(generated.map { it.date },scenario)
    val latest = candles.last()
    val minutes = List(242) { i ->
        val m = if (i <= 120) 570 + i else 780 + i - 121
        MinutePoint("${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}", latest.open + (latest.close-latest.open)*i/241 + sin(i*.09)*sin(PI*i/241)*latest.open*.003)
    }
    return StockDetail(quote, candles, minutes, false, leader = leader, currency = if (quote.symbol.all { it.isDigit() }) "CNY" else "USD", funds = demoStockFunds(quote, candles))
}

/** Synthetic OHLC scenarios used by the demo; evaluated by the same engine as production. */
internal fun demoScenario(dates: List<String>, scenario: Int): List<Candle> {
    val bars = dates.mapIndexed { i,date ->
        val t = i-(dates.size-100)
        val close = if (t < 0) (when(scenario) { 0 -> 35.0; 1,4 -> 110.0; 2 -> 30.0; else -> 50.0 }) + t*.03 else when (scenario) {
            0 -> 35+t*.35
            1,4 -> 110-t*.35
            2 -> if(t<80) 30+t*.5 else if(t<89) 69-(t-80)*.35 else 66+sin(t.toDouble())*.2
            else -> 50+t*.15
        }
        val open = close + if (scenario in listOf(1,4)) .2 else -.2
        val volume = if (scenario==2 && t>=89) 40000.0 else 100000.0
        Candle(date,open,max(open,close)+.3,min(open,close)-.3,close,volume,volume*100*close,"模拟行情","lot_100_shares")
    }.toMutableList()
    val previous = bars[bars.lastIndex-1]
    val last = bars.last()
    bars[bars.lastIndex] = when(scenario) {
        0 -> last.copy(open=previous.close+.2,close=previous.open-1.2,high=previous.close+.5,low=previous.open-1.5,volume=160000.0)
        3 -> {
            val high = bars.dropLast(1).takeLast(20).maxOf { it.high }
            last.copy(open=previous.close,close=high*1.02,high=high*1.02+.1,low=previous.close-.2,volume=200000.0)
        }
        4 -> last.copy(open=previous.close-.2,close=previous.open+1.2,high=previous.open+1.5,low=previous.close-.5,volume=160000.0)
        else -> last
    }
    return bars.mapIndexed { i,b -> b.copy(amount=b.volume!!*100*b.close,change=if(i==0) null else (b.close/bars[i-1].close-1)*100) }
}
