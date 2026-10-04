package cn.gproject

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.datetime.LocalDate

/** Exhaust the server catalog; reject broken or changing pagination instead of claiming completion. */
internal suspend fun MarketRepository.catalog(kind: String = "stock"): List<JsonObject> {
    val result=mutableListOf<JsonObject>()
    val seen=mutableSetOf<String>()
    var expected: Int?=null
    do {
        currentCoroutineContext().ensureActive()
        val page=get("/v1/instruments",mapOf("market" to "CN","kind" to kind,"limit" to "500","offset" to result.size.toString()))
        val rawTotal=requireNotNull(page.num("total")) { "目录未提供总数" }
        require(rawTotal>=0 && rawTotal<=Int.MAX_VALUE && rawTotal%1.0==0.0) { "目录总数无效" }
        val total=rawTotal.toInt()
        if(expected==null) expected=total else require(expected==total) { "目录在扫描期间发生变化，请重新扫描" }
        val rows=page.rows()
        require(result.size+rows.size<=total && (rows.isNotEmpty() || result.size==total)) { "目录分页不完整" }
        rows.forEach { row ->
            val id=row.str("id")
            require(id.startsWith("CN.$kind.") && row.str("symbol").isNotBlank() && seen.add(id)) { "目录存在重复或错误标识" }
            if(kind=="stock") require(row.str("symbol").matches(Regex("[0-9]{6}")) && id=="CN.stock.${row.str("symbol")}") { "股票标识与代码不一致" }
        }
        result.addAll(rows)
    } while(result.size<expected!!)
    return result
}

data class ScanProgress(val total: Int, val processed: Int, val valid: Int, val excluded: Int, val date: String)

internal suspend fun MarketRepository.scanUniverse(rules: Rules, progress: (ScanProgress,List<Signal>) -> Unit): List<Signal> {
    val date=get("/v1/cn/rankings",mapOf("limit" to "1")).str("trade_date").also { LocalDate.parse(it) }
    val instruments=catalog()
    val signals=mutableListOf<Signal>()
    var processed=0
    progress(ScanProgress(instruments.size,0,0,0,date),emptyList())
    for(batch in instruments.chunked(4)) {
        val results=coroutineScope { batch.map { row -> async {
            try {
                val bars=get("/v1/bars/${row.str("id")}",mapOf("end" to date,"limit" to "250")).rows().map { b ->
                    Bar(b.str("trade_date"),requireNotNull(b.num("close")),requireNotNull(b.num("high")),b.num("volume"),b.str("source"),b.num("open"),b.num("low"),b.str("volume_unit"))
                }
                if(bars.lastOrNull()?.date!=date) null else Indicators.calculate(Quote(row.str("id"),row.str("name"),row.str("symbol")),bars,rules)
                    ?.takeIf { rules.preset==StrategyPreset.CUSTOM || it.technicalReady }
            } catch(e: CancellationException) { throw e } catch(e: ApiFailure) { if(e.status==401) throw e else null } catch(_: Exception) { null }
        } }.awaitAll() }
        currentCoroutineContext().ensureActive()
        processed+=batch.size
        signals.addAll(results.filterNotNull())
        progress(ScanProgress(instruments.size,processed,signals.size,processed-signals.size,date),signals.toList())
    }
    return signals
}
