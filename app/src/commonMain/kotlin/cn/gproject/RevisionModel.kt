package cn.gproject

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

data class DailyUiState(
    val connected: Boolean = false, val loading: Boolean = false, val base: String = "https://ericcself.top",
    val overview: DailyOverview = DailyOverview(), val rankings: Map<String,DailyRanking> = emptyMap(),
    val topics: List<FocusTopic> = emptyList(), val funds: Map<String,List<DailyFund>> = emptyMap(), val macro: List<MacroQuote> = emptyList(),
    val agenda: ImportantAgenda = ImportantAgenda(), val watchQuotes: Map<String,WatchClose> = emptyMap(), val watchLoading: Boolean = false,
    val calendar: List<CalendarEntry> = emptyList(), val policy: List<PolicyEntry> = emptyList(), val calendarNote: String = "",
    val indices: List<Quote> = emptyList(), val usAi: List<DailyAsset> = emptyList(), val usSectors: List<DailyAsset> = emptyList(),
    val leaders: List<Leader> = emptyList(), val watched: List<DailyAsset> = emptyList(),
    val search: List<DailyAsset> = emptyList(), val searching: Boolean = false,
    val detail: DailyDetail? = null, val errors: Map<String,String> = emptyMap(), val message: String = "", val preview: Boolean = false,
)

internal fun loadDailyWatch(): List<DailyAsset> = runCatching {
    val json = Json.parseToJsonElement(readWorkspacePreferences() ?: "{}").jsonObject
    (json["stocks"] as? JsonArray).orEmpty().map { it.jsonObject }.mapNotNull { row ->
        val id = row.str("id")
        if (runCatching { checkedAssetId(id) }.isFailure) null else DailyAsset(id, row.str("name"), row.str("symbol"))
    }.distinctBy { it.id }.take(100)
}.getOrDefault(emptyList())

internal fun encodeDailyWatch(items: List<DailyAsset>): String = buildJsonObject {
    put("version", 3)
    putJsonArray("stocks") { items.forEach { item -> add(buildJsonObject {
        put("id", item.id); put("name", item.name); put("symbol", item.symbol)
    }) } }
}.toString()

class RevisedViewModel(private val api: MarketRepository = MarketRepository(), initial: DailyUiState? = null) : ViewModel() {
    private val state = MutableStateFlow(initial ?: DailyUiState(watched = loadDailyWatch()))
    val ui = state.asStateFlow()
    private var work: Job? = null
    private var detailWork: Job? = null
    private var searchWork: Job? = null
    private var watchWork: Job? = null
    private var watchGeneration = 0
    private var generation = 0
    private var detailGeneration = 0
    private var searchGeneration = 0

    fun connect(base: String, token: String) {
        val address = base.trim().trimEnd('/')
        if (!address.matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]+)?(?:/[A-Za-z0-9_./-]*)?")) || token.isBlank()) {
            state.update { it.copy(message = "请输入 HTTPS 服务地址和访问令牌") }; return
        }
        generation++; detailGeneration++; searchGeneration++; watchGeneration++
        work?.cancel(); detailWork?.cancel(); searchWork?.cancel(); watchWork?.cancel()
        api.base = address; api.token = token.trim()
        state.value = DailyUiState(connected = true, base = address, watched = state.value.watched)
        refresh()
    }

    fun disconnect() {
        generation++; detailGeneration++; searchGeneration++; watchGeneration++
        work?.cancel(); detailWork?.cancel(); searchWork?.cancel(); watchWork?.cancel(); api.token = ""
        state.value = DailyUiState(base = state.value.base, watched = state.value.watched)
    }

    fun refresh() {
        if (!state.value.connected || state.value.preview) return
        work?.cancel(); val revision = ++generation
        state.update { it.copy(loading = true, message = "") }
        work = viewModelScope.launch {
            try {
                val overview = api.dailyOverview()
                if (revision != generation) return@launch
                state.update { it.copy(overview = overview, errors = it.errors - "连接") }
                refreshWatch()
                val gate = Semaphore(3)
                suspend fun section(key: String, action: suspend () -> Unit) {
                    try {
                        gate.withPermit { action() }
                        if (revision == generation) state.update { it.copy(errors = it.errors - key) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (revision == generation) state.update { it.copy(errors = it.errors + (key to failureMessage(e))) } }
                }
                coroutineScope {
                    for (kind in listOf("stock","etf")) {
                        val day = if (kind in listOf("industry","concept")) overview.boards else overview.cn
                        if (day.isNotBlank()) launch { section(dailyKindLabel(kind)+"涨幅榜") {
                            val data = api.dailyRanking(kind, day)
                            if (revision == generation) state.update { it.copy(rankings = it.rankings + (kind to data)) }
                        } }
                    }
                    if ("focus_boards" in overview.features && overview.boards.isNotBlank()) launch { section("关注板块") {
                        val data=api.dailyFocus(overview.boards)
                        if(revision==generation) state.update { it.copy(topics=data) }
                    } }
                    if ("macro" in overview.features) launch { section("宏观数据") { val data = api.dailyMacro(); if (revision == generation) state.update { it.copy(macro = data) } } }
                    if ("calendar" in overview.features) launch { section("经济日历") { val data = api.dailyCalendar(); if (revision == generation) state.update { it.copy(calendar = data.items,
                        calendarNote=when { !data.covered -> "当前快照未覆盖全部未来日期，以已列出的事件为准。"; data.stale -> "日历更新暂时失败，当前为保留快照。"; else -> "" }) } } }
                    if ("important_calendar" in overview.features) launch { section("重要日程") {
                        val data=api.importantAgenda();if(revision==generation) state.update { it.copy(agenda=data) }
                    } }
                    if ("priority_news" in overview.features) launch { section("政策新闻") { val data = api.dailyPolicy(); if (revision == generation) state.update { it.copy(policy = data) } } }
                    launch { section("指数") { val data = api.referenceIndices().filter { it.price != null }; if (revision == generation) state.update { it.copy(indices = data) } } }
                    if (overview.us.isNotBlank()) for ((endpoint,label) in listOf("ai" to "美股观察","sectors" to "美股行业")) launch { section(label) {
                        val data = api.get("/v1/us/$endpoint", mapOf("trade_date" to overview.us)).rows().filter { it.str("status") == "current" && it.num("close") != null }.map { row ->
                            val symbol = row.str("symbol")
                            DailyAsset("US.${if(endpoint=="ai") "stock" else "etf"}.$symbol", if(endpoint=="ai") symbol else row.str("group"), symbol,
                                row.num("close"), row.num("change_1d_pct"), row.str("trade_date"), row.str("source"),periodSessions=1)
                        }
                        if (revision == generation) state.update { if (endpoint == "ai") it.copy(usAi = data) else it.copy(usSectors = data) }
                    } }
                    if (overview.lhb.isNotBlank()) launch { section("龙虎榜") {
                        val data = api.dailyPages("/v1/cn/lhb", mapOf("trade_date" to overview.lhb))
                        val leaders = data.rows().map { Leader(it.str("name"), it.str("symbol"), it.num("net_amount"), it.str("reason"), overview.lhb) }
                        if (revision == generation) state.update { it.copy(leaders = leaders) }
                    } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == generation) state.update { it.copy(errors = it.errors + ("连接" to failureMessage(e))) } }
            finally { if (revision == generation) state.update { it.copy(loading = false) } }
        }
    }

    fun search(query: String) {
        searchWork?.cancel(); val revision = ++searchGeneration
        if (query.trim().length < 2) { state.update { it.copy(search = emptyList(), searching = false) }; return }
        if (!state.value.connected || state.value.preview) return
        state.update { it.copy(searching = true) }
        searchWork = viewModelScope.launch {
            try {
                val data = api.get("/v1/instruments", mapOf("market" to "CN", "kind" to "stock", "search" to query.trim(), "available_only" to "true", "limit" to "20", "refresh" to "true"))
                val rows = data.rows().map { DailyAsset(checkedAssetId(it.str("id")), it.str("name"), it.str("symbol"), date = state.value.overview.cn) }
                if (revision == searchGeneration) state.update { it.copy(search = rows, searching = false, errors = it.errors - "搜索") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == searchGeneration) state.update { it.copy(searching = false, errors = it.errors + ("搜索" to failureMessage(e))) } }
        }
    }

    fun open(asset: DailyAsset) {
        if (!state.value.connected && !state.value.preview) { state.update { it.copy(message="请先连接数据服务") }; return }
        detailWork?.cancel(); val revision = ++detailGeneration
        if (state.value.preview) { state.update { it.copy(detail = DailyDetail(asset, loading = false)) }; return }
        val overview = state.value.overview
        state.update { it.copy(detail = DailyDetail(asset), message = "") }
        detailWork = viewModelScope.launch {
            try {
                var data = api.dailyDetail(asset, overview)
                if (revision != detailGeneration) return@launch
                state.update { it.copy(detail = data) }
                if (asset.id in overview.detailFeatures["metrics"].orEmpty()) {
                    val metrics = api.metricsFeed(asset.id, data.candles.lastOrNull()?.date ?: overview.cn)
                    if (metrics.error == null) data = data.copy(metrics = metrics)
                }
                if(asset.id.startsWith("CN.stock.") && "intraday" in overview.features) {
                    try { data=data.copy(intraday=api.dailyIntraday(asset.id,overview.cn)) }
                    catch(e: CancellationException) { throw e }
                    catch(_: Exception) { data=data.copy(intradayError="该日分时快照暂不可用，日线仍可查看。") }
                }
                val board = (asset.id.contains(".industry.") || asset.id.contains(".concept.")) && !asset.symbol.startsWith("THS_")
                if (board && overview.members.isNotBlank()) {
                    try { data = data.copy(members = api.dailyMembers(asset.id, overview.members)) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                if (asset.id in overview.detailFeatures["funds"].orEmpty() || board) {
                    try {
                        val response = api.get(if (board) "/v1/cn/boards/${asset.id}/flows" else "/v1/cn/stocks/${asset.id}/flows",
                            mapOf("limit" to "30", "end" to (data.candles.lastOrNull()?.date ?: overview.cn)))
                        require(response.str("unit") == "yuan" && response.str("currency") == "CNY")
                        data = data.copy(funds = response.rows().map { checkedDate(it.str("trade_date")) to requireNotNull(it.num("net")) })
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
                if (revision == detailGeneration) state.update { it.copy(detail = data) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (revision == detailGeneration) state.update { it.copy(detail = DailyDetail(asset, loading = false, error = failureMessage(e))) } }
        }
    }

    fun back() { detailGeneration++; detailWork?.cancel(); state.update { it.copy(detail = null) } }
    fun toggleWatch(asset: DailyAsset) {
        val rows = state.value.watched.let { if (it.any { row -> row.id == asset.id }) it.filterNot { row -> row.id == asset.id } else (it + asset).takeLast(100) }
        state.update { it.copy(watched = rows,watchQuotes=it.watchQuotes.filterKeys { id -> rows.any { it.id==id } }) }
        refreshWatch()
        if (!state.value.preview) runCatching { writeWorkspacePreferences(encodeDailyWatch(rows)) }.onFailure { state.update { it.copy(message = "自选暂未保存，本次会话仍然有效") } }
    }
    fun refreshWatch() {
        watchWork?.cancel();val revision=++watchGeneration
        val current=state.value
        if(!current.connected || current.preview || current.watched.isEmpty()) { state.update { it.copy(watchLoading=false) };return }
        val requested=current.watched;val overview=current.overview
        state.update { it.copy(watchLoading=true) }
        watchWork=viewModelScope.launch {
            try {
                val quotes=api.watchCloses(requested,overview)
                if(revision==watchGeneration) state.update { it.copy(watchQuotes=quotes,errors=it.errors-"自选报价") }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(revision==watchGeneration) state.update { it.copy(errors=it.errors+("自选报价" to failureMessage(e))) } }
            finally { if(revision==watchGeneration) state.update { it.copy(watchLoading=false) } }
        }
    }
    fun dismissMessage() { state.update { it.copy(message = "") } }
    suspend fun seats(leader: Leader) = api.seatFeed(leader.symbol, leader.date)
    fun close() { generation++; detailGeneration++; searchGeneration++; watchGeneration++; work?.cancel(); detailWork?.cancel(); searchWork?.cancel(); watchWork?.cancel(); api.close() }
    override fun onCleared() { close() }
}

internal fun failureMessage(e: Exception) = if (e is ApiFailure && e.status == 401) "访问令牌无效，请重新连接" else "暂时读取失败，请重试；已有快照会保留"
internal fun dailyKindLabel(kind: String) = when(kind) { "stock" -> "个股"; "etf" -> "ETF"; "industry" -> "行业"; else -> "概念" }
