package cn.gproject

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.datetime.Clock

data class WatchedStock(val id: String,val name: String,val symbol: String,val pinned: Boolean=false,val addedAt: String=Clock.System.now().toString()) {
    fun quote()=Quote(id,name,symbol)
}
data class AiConfiguration(val provider: String="自定义国内模型",val baseUrl: String="",val model: String="")
data class WorkspaceSettings(val stocks: List<WatchedStock> = emptyList(),val ai: AiConfiguration=AiConfiguration())
data class WatchContext(val quotes: Map<String,Quote> = emptyMap(),val associations: Map<String,List<Board>> = emptyMap(),val boardQuotes: Map<String,Quote> = emptyMap(),val note: String="加入标的后同步关联板块",val errors: Map<String,String> = emptyMap())
data class WorkspaceState(val settings: WorkspaceSettings=WorkspaceSettings(),val context: WatchContext=WatchContext(),val message: String="",val aiKeyPresent: Boolean=false,val removedStock: WatchedStock?=null)
internal fun orderedWatchlist(stocks: List<WatchedStock>)=stocks.sortedWith(compareByDescending<WatchedStock> { it.pinned }.thenByDescending { it.addedAt }.thenBy { it.id })

internal expect fun readWorkspacePreferences(): String?
internal expect fun writeWorkspacePreferences(value: String)
internal fun encodeWorkspace(settings: WorkspaceSettings): String=buildJsonObject {
    putJsonArray("stocks") { settings.stocks.filter { !it.id.startsWith("demo") }.forEach { stock -> add(buildJsonObject {
        put("id",stock.id);put("name",stock.name);put("symbol",stock.symbol);put("pinned",stock.pinned);put("added_at",stock.addedAt)
    }) } }
    putJsonObject("ai") { put("provider",settings.ai.provider);put("base_url",settings.ai.baseUrl);put("model",settings.ai.model) }
}.toString()
internal fun decodeWorkspace(text: String): WorkspaceSettings {
    val root=Json.parseToJsonElement(text).jsonObject
    val stocks=(root["stocks"] as? JsonArray).orEmpty().map { it.jsonObject }.mapNotNull { row ->
        val id=row.str("id");val symbol=row.str("symbol")
        if(id!="CN.stock.$symbol" || !symbol.matches(Regex("[0-9]{6}"))) null else WatchedStock(id,row.str("name"),symbol,row["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,row.str("added_at"))
    }.distinctBy { it.id }
    val ai=root["ai"] as? JsonObject
    return WorkspaceSettings(stocks,AiConfiguration(ai?.str("provider") ?: "自定义国内模型",ai?.str("base_url").orEmpty(),ai?.str("model").orEmpty()))
}
internal fun loadWorkspaceSettings()=runCatching { decodeWorkspace(readWorkspacePreferences() ?: "{}") }.getOrDefault(WorkspaceSettings())

/** Reserved extension point: no analysis request or financial data is sent by this version. */
enum class AnalysisKind { SENTIMENT, IMPORTANT_EVENT, EARNINGS }
data class AnalysisRequest(val kind: AnalysisKind,val instrumentId: String,val text: String,val sourceUrls: List<String>)
data class AnalysisResult(val summary: String,val sourceUrls: List<String>,val generatedAt: String)
interface MarketAnalysisProvider { suspend fun analyze(request: AnalysisRequest): AnalysisResult }
internal fun validateAiConfiguration(config: AiConfiguration): String? = when {
    !config.baseUrl.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]+)?(?:/[A-Za-z0-9_./-]*)?")) -> "请输入不含密钥、查询参数或用户名的 HTTPS API 根地址"
    config.model.isBlank() || config.model.length>200 -> "请输入有效模型名称"
    else -> null
}

internal data class MembershipIndex(val date: String,val bySymbol: Map<String,List<Board>>,val note: String,val complete: Boolean=false)
internal suspend fun MarketRepository.membershipIndex(date: String): MembershipIndex {
    val boards=catalog("industry").filter { it.str("id").startsWith("CN.industry.THS_") }
    val index=mutableMapOf<String,MutableList<Board>>()
    var incomplete=0
    for(batch in boards.chunked(4)) {
        val results=coroutineScope { batch.map { board -> async {
            try {
                val symbols=linkedSetOf<String>();var offset=0;var complete=true
                do {
                    val response=get("/v1/cn/boards/${board.str("id")}/members",mapOf("observed_date" to date,"limit" to "500","offset" to offset.toString()))
                    require(response.str("trade_date")==date) { "成分日期不一致" }
                    val rows=response.rows()
                    val states=(response["task_status"] as? JsonArray).orEmpty().map { it.jsonObject.str("status") }
                    if(states.isEmpty() || states.any { it!="complete" }) complete=false
                    if(rows.isEmpty() && offset==0) complete=false
                    rows.forEach { r -> val symbol=r.str("symbol");require(symbol.matches(Regex("[0-9]{6}")) && symbols.add(symbol)) { "成分分页重复或标识异常" } }
                    offset+=rows.size
                } while(rows.size==500)
                Triple(Board(board.str("id"),board.str("name"),null),symbols.toList(),complete)
            } catch(e: CancellationException) { throw e } catch(e: ApiFailure) { if(e.status==401) throw e else null } catch(_: Exception) { null }
        } }.awaitAll() }
        results.forEach { entry ->
            if(entry==null) incomplete++ else {
                if(!entry.third) incomplete++
                entry.second.forEach { index.getOrPut(it) { mutableListOf() }.add(entry.first) }
            }
        }
    }
    return MembershipIndex(date,index,"$date · 已核对 ${boards.size} 个同花顺行业，$incomplete 个快照未完整确认；只展示已获取的成分关系",boards.isNotEmpty() && incomplete==0)
}

internal suspend fun MarketRepository.watchContext(stocks: List<WatchedStock>, index: MembershipIndex): WatchContext {
    val associations=stocks.associate { it.id to index.bySymbol[it.symbol].orEmpty() }
    val boards=associations.values.flatten().distinctBy { it.id }
    val inputs=stocks.map { it.quote() }+boards.map { Quote(it.id,it.name,it.id.substringAfterLast(".")) }
    val quotes=mutableMapOf<String,Quote>();val errors=mutableMapOf<String,String>()
    for(batch in inputs.distinctBy { it.id }.chunked(4)) {
        val results=coroutineScope { batch.map { q -> async {
            try {
                val bars=get("/v1/bars/${q.id}",mapOf("end" to index.date,"limit" to "40")).rows()
                require(bars.isNotEmpty()) { "暂无日线" }
                val last=bars.last();val closes=bars.map { requireNotNull(it.num("close")) }
                require(closes.all { it>0 } && bars.all { it.str("source").isNotBlank() } && bars.map { it.str("source") }.distinct().size==1 && bars.zipWithNext().all { it.first.str("trade_date")<it.second.str("trade_date") }) { "行情格式异常" }
                bars.forEach { kotlinx.datetime.LocalDate.parse(it.str("trade_date")) }
                q.copy(price=closes.last(),change=last.num("change_pct"),date=last.str("trade_date"),source=last.str("source"),trend=closes) to null
            } catch(e: CancellationException) { throw e } catch(e: ApiFailure) { if(e.status==401) throw e else q to "行情更新失败" } catch(_: Exception) { q to "行情缺失或格式异常" }
        } }.awaitAll() }
        results.forEach { (q,error) -> if(error!=null) errors[q.id]=error else quotes[q.id]=q }
    }
    val linked=associations.mapValues { (_,list) -> list.map { it.copy(change=quotes[it.id]?.change,date=quotes[it.id]?.date ?: index.date) } }
    return WatchContext(quotes.filterKeys { id -> stocks.any { it.id==id } },linked,quotes.filterKeys { id -> boards.any { it.id==id } },index.note,errors)
}

internal fun demoWatchContext(stocks: List<WatchedStock>): WatchContext {
    val associations=stocks.associate { stock -> stock.id to listOf(Demo.boards[when(stock.symbol) { "688981" -> 0; "300308" -> 1; "300750" -> 2; "600036" -> 4; else -> 3 }]) }
    return WatchContext(stocks.associate { it.id to demoStock(it.quote()).snapshotQuote() },associations,
        associations.values.flatten().distinctBy { it.id }.associate { b -> b.id to Quote(b.id,b.name,"模拟板块",1000.0,b.change,"演示快照","模拟行业",List(30) { 950+it*1.6+kotlin.math.sin(it*.4)*14 }) },
        "模拟板块关系与走势 · 非真实归属")
}
