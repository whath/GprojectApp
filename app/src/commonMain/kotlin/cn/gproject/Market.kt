package cn.gproject

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

data class Quote(
    val id: String,
    val name: String,
    val symbol: String,
    val price: Double? = null,
    val change: Double? = null,
    val date: String = "",
    val source: String = "",
    val trend: List<Double> = emptyList(),
)

data class Board(val id: String, val name: String, val change: Double?,val date: String="")

data class Leader(val name: String, val symbol: String, val net: Double?, val reason: String,val date: String="")

data class Bar(
    val date: String,
    val close: Double,
    val high: Double,
    val volume: Double?,
    val source: String,
    val open: Double? = null,
    val low: Double? = null,
    val volumeUnit: String = "unknown",
)

data class Rules(
    val ma: Int = 20,
    val aboveMa: Boolean = true,
    val rsiMin: Int = 40,
    val rsiMax: Int = 75,
    val volume: Double = 1.0,
    val breakout: Boolean = false,
    val preset: StrategyPreset = StrategyPreset.CUSTOM,
    val parameters: StrategyParameters = StrategyParameters(),
)

data class Signal(
    val quote: Quote,
    val ma: Double,
    val rsi: Double,
    val volume: Double,
    val breakout: Boolean,
    val setups: List<SetupHit> = emptyList(),
    val technicalReady: Boolean = false,
)

data class ScreenState(
    val sessionId: Int=0,
    val hasConnection: Boolean=false,
    val serviceUrl: String="",
    val sectionErrors: Map<String,String> = emptyMap(),
    val leaderDate: String="",
    val demo: Boolean = false,
    val loading: Boolean = false,
    val date: String = "待连接",
    val boards: List<Board> = emptyList(),
    val leaders: List<Leader> = emptyList(),
    val us: List<Quote> = emptyList(),
    val signals: List<Signal> = emptyList(),
    val rules: Rules = Rules(),
    val error: String? = null,
    val note: String = "连接收盘数据服务，或开启演示体验",
    val selected: Board? = null,
    val members: List<Quote> = emptyList(),
    val memberNote: String = "",
    val scanning: Boolean = false,
    val scanNote: String = "全量 A 股目录扫描 · 分页读取全部已登记股票，实际覆盖取决于后端目录与行情完整度",
    val scanProgress: ScanProgress? = null,
    val scanPhase: ScanPhase=ScanPhase.IDLE,
    val boardDetail: Boolean = false,
    val stock: StockDetail? = null,
    val monitor: MonitorState = MonitorState(),
    val workspace: WorkspaceState = WorkspaceState(),
)

object Indicators {
    // Wilder RSI; all windows use observed daily bars, never silently invent missing sessions.
    fun calculate(q: Quote, bars: List<Bar>, rules: Rules): Signal? {
        if (
            rules.ma !in DAILY_MA_PERIODS || bars.size < max(21, rules.ma) ||
                bars.any {
                    !it.close.isFinite() ||
                        it.close <= 0 ||
                        !it.high.isFinite() ||
                        it.high < it.close
                } ||
                bars.any { it.source.isBlank() } || bars.map { it.source }.distinct().size != 1
        )
            return null
        if (bars.zipWithNext().any { it.first.date >= it.second.date }) return null
        val ma = bars.takeLast(rules.ma).map { it.close }.average()
        val changes = bars.zipWithNext { a, b -> b.close - a.close }
        var gain = changes.take(14).sumOf { max(it, 0.0) } / 14
        var loss = changes.take(14).sumOf { max(-it, 0.0) } / 14
        changes.drop(14).forEach {
            gain = (gain * 13 + max(it, 0.0)) / 14
            loss = (loss * 13 + max(-it, 0.0)) / 14
        }
        val rsi =
            if (loss == 0.0) {
                if (gain == 0.0) 50.0 else 100.0
            } else 100 - 100 / (1 + gain / loss)
        val prev = bars.dropLast(1).takeLast(20)
        val units=bars.takeLast(21).map { it.volumeUnit }.distinct()
        if(units.size!=1 || units.single() !in setOf("share","lot_100_shares")) return null
        if (
            prev.any { it.volume == null || !it.volume.isFinite() || it.volume < 0 } ||
                bars.last().volume == null
        )
            return null
        val avg = prev.map { it.volume!! }.average()
        if (avg <= 0) return null
        val ratio = bars.last().volume!! / avg
        if (!ratio.isFinite() || ratio < 0) return null
        val breakout = bars.last().close > prev.maxOf { it.high }
        val setups = StrategyEngine.evaluate(bars, rules.parameters)
        return Signal(
            q.copy(
                price = bars.last().close,
                date = bars.last().date,
                source = bars.last().source,
                trend = bars.takeLast(40).map { it.close },
            ),
            ma,
            rsi,
            ratio,
            breakout,
            setups.orEmpty(),
            setups != null,
        )
    }

    fun matches(s: Signal, r: Rules) =
        if (r.preset != StrategyPreset.CUSTOM) s.technicalReady && s.setups.any { it.preset == r.preset }
        else (!r.aboveMa || s.quote.price!! > s.ma) &&
            s.rsi >= r.rsiMin &&
            s.rsi <= r.rsiMax &&
            s.volume >= r.volume &&
            (!r.breakout || s.breakout)
}

internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.num(k: String) =
    (this[k] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

internal fun JsonObject.rows() =
    (this["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

class ApiFailure(val status: Int, message: String) : IllegalStateException(message)

class MarketRepository(
    private val client: HttpClient = HttpClient {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 20000
            connectTimeoutMillis = 10000
        }
    }
) : AutoCloseable {
    var base = ""
    var token = ""

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        val response =
            client.get(base.trimEnd('/') + path) {
                header("Authorization", "Bearer $token")
                params.forEach { (k, v) -> parameter(k, v) }
            }
        if (response.status.value == 401) throw ApiFailure(401,"令牌无效或已过期，请重新连接")
        if (response.status.value !in 200..299) throw ApiFailure(response.status.value,"服务请求失败（${response.status.value}），请稍后重试")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    override fun close() {
        token = ""
        client.close()
    }
}

class MarketViewModel(private val api: MarketRepository = MarketRepository(), initialMonitorSettings: MonitorSettings = loadMonitorSettings(), initialWorkspaceSettings: WorkspaceSettings = loadWorkspaceSettings()) : ViewModel() {
    private val state = MutableStateFlow(ScreenState(monitor = MonitorState(settings = initialMonitorSettings),workspace = WorkspaceState(settings=initialWorkspaceSettings)))
    val ui = state.asStateFlow()
    private var request: Job? = null
    private var scan: Job? = null
    private var memberJob: Job? = null
    private var stockJob: Job? = null
    private var monitoringJob: Job? = null
    private var intelligenceJob: Job? = null
    private var intelligenceGeneration = 0
    private var intelligencePending = false
    private var active = false
    private var candidates = emptyList<Signal>()
    private var scanGeneration=0
    private var membershipCache: MembershipIndex?=null
    private var aiSessionKey=""
    private var requestGeneration=0
    private var stockGeneration=0
    private var memberGeneration=0

    fun close() {
        requestGeneration++;stockGeneration++;memberGeneration++
        scanGeneration++
        aiSessionKey=""
        intelligenceGeneration++
        request?.cancel()
        scan?.cancel()
        memberJob?.cancel()
        stockJob?.cancel()
        monitoringJob?.cancel()
        intelligenceJob?.cancel()
        api.close()
    }

    override fun onCleared() {
        close()
    }

    fun connect(base: String, token: String) {
        if (
            !base
                .trim()
                .matches(Regex("https://[A-Za-z0-9.-]+(?::[0-9]+)?(?:/[A-Za-z0-9_./-]*)?")) ||
                token.isBlank()
        ) {
            state.update { it.copy(error = "请输入 HTTPS 服务地址及访问令牌") }
            return
        }
        scanGeneration++
        requestGeneration++;stockGeneration++;memberGeneration++
        aiSessionKey=""
        membershipCache=null
        request?.cancel()
        scan?.cancel()
        memberJob?.cancel()
        candidates = emptyList()
        stockJob?.cancel()
        api.base = base.trim()
        api.token = token.trim()
        intelligenceGeneration++
        intelligencePending = false
        intelligenceJob?.cancel()
        state.value = ScreenState(sessionId=state.value.sessionId+1,hasConnection=true,serviceUrl=api.base,loading = true, rules = state.value.rules, monitor = MonitorState(settings = loadMonitorSettings()),workspace = WorkspaceState(settings=loadWorkspaceSettings()))
        refresh()
    }

    fun demo() {
        requestGeneration++;stockGeneration++;memberGeneration++
        aiSessionKey=""
        scanGeneration++
        membershipCache=null
        intelligenceGeneration++
        intelligencePending = false
        stockJob?.cancel()
        intelligenceJob?.cancel()
        request?.cancel()
        scan?.cancel()
        memberJob?.cancel()
        api.token = ""
        candidates = Demo.signals(Rules())
        state.value =
            ScreenState(
                sessionId=state.value.sessionId+1,serviceUrl=state.value.serviceUrl,scanPhase=ScanPhase.COMPLETE,
                demo = true,
                workspace = WorkspaceState(settings=WorkspaceSettings(ai=state.value.workspace.settings.ai),aiKeyPresent=aiSessionKey.isNotBlank()),
                date = "演示快照",
                leaderDate="演示快照",
                boards = Demo.boards,
                leaders = Demo.leaders,
                us = Demo.us,
                rules = Rules(),
                note = "演示数据 · 仅用于体验界面，不是实际行情",
                scanNote = "演示扫描目录 · 6 个模拟标的",
                monitor = MonitorState(settings = MonitorSettings(watched = Demo.boards.take(2)), flows = Demo.boards.take(2).associate { it.id to demoFlows(it.id) }, feed = demoEvents(), note = "演示监控 · 不是真实资金或事件"),
            )
        filter(Rules())
    }

    fun disconnect() {
        requestGeneration++;stockGeneration++;memberGeneration++;scanGeneration++;intelligenceGeneration++
        request?.cancel();stockJob?.cancel();memberJob?.cancel();scan?.cancel();intelligenceJob?.cancel()
        intelligencePending=false;membershipCache=null;candidates=emptyList();aiSessionKey="";api.token=""
        state.value=ScreenState(sessionId=state.value.sessionId+1,serviceUrl=state.value.serviceUrl,
            monitor=MonitorState(settings=loadMonitorSettings()),workspace=WorkspaceState(settings=loadWorkspaceSettings()))
    }

    fun refresh() {
        refreshIntelligence()
        if(state.value.demo) { scan();return }
        if(api.token.isBlank()) return
        request?.cancel()
        val generation=++requestGeneration
        state.update { it.copy(loading=true,error=null) }
        request=viewModelScope.launch {
            suspend fun section(name: String,action: suspend ()->Unit) {
                try {
                    action()
                    if(generation==requestGeneration) state.update { it.copy(sectionErrors=it.sectionErrors-name) }
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) {
                    if(generation==requestGeneration) state.update { it.copy(sectionErrors=it.sectionErrors+(name to (if(e is ApiFailure) e.message.orEmpty() else "更新失败，保留上次快照，请重试"))) }
                }
            }
            try {
                coroutineScope {
                    launch { section("板块") {
                        val response=api.get("/v1/cn/rankings",mapOf("limit" to "50","classification_source" to "ths"))
                        val date=response.str("trade_date").also { kotlinx.datetime.LocalDate.parse(it) }
                        val boards=response.rows().map { Board(it.str("id"),it.str("name"),it.num("price_change_pct"),date) }
                        if(generation==requestGeneration) {
                            val changed=state.value.date!=date
                            state.update { s -> s.copy(date=date,boards=boards,selected=s.selected?.let { old -> boards.find { it.id==old.id } ?: old },note="同花顺行业 · 已采集范围前 50 · 不复权收盘价变化") }
                            if(changed && state.value.boardDetail) state.value.selected?.let(::select)
                        }
                    } }
                    launch { section("龙虎榜") {
                        val response=api.get("/v1/cn/lhb",mapOf("limit" to "50"))
                        val date=response.str("trade_date").also { kotlinx.datetime.LocalDate.parse(it) }
                        val leaders=response.rows().map { Leader(it.str("name"),it.str("symbol"),it.num("net_amount"),it.str("reason"),date) }
                        if(generation==requestGeneration) state.update { it.copy(leaders=leaders,leaderDate=date) }
                    } }
                    launch { section("美股") {
                        val response=api.get("/v1/us/ai")
                        val quotes=response.rows().map { Quote("US.stock.${it.str("symbol")}",it.str("group").ifBlank { it.str("symbol") },it.str("symbol"),it.num("close"),it.num("change_1d_pct"),it.str("trade_date"),it.str("source")) }
                        if(generation==requestGeneration) state.update { it.copy(us=quotes) }
                    } }
                }
            } finally { if(generation==requestGeneration) state.update { it.copy(loading=false) } }
        }
    }
    fun openBoard(board: Board) {
        state.update { it.copy(boardDetail = true) }
        select(board)
    }

    fun back(): Boolean {
        if (state.value.stock != null) {
            stockGeneration++
            stockJob?.cancel()
            state.update { it.copy(stock = null) }
            return true
        }
        if (state.value.boardDetail) {
            state.update { it.copy(boardDetail = false) }
            return true
        }
        return false
    }

    fun openLeader(leader: Leader) = openStock(Quote("CN.stock.${leader.symbol}", leader.name, leader.symbol), leader)

    fun openStock(quote: Quote, leader: Leader? = null) {
        stockJob?.cancel()
        val generation=++stockGeneration
        state.update { it.copy(stock = StockDetail(quote, leader = leader)) }
        if (state.value.demo) {
            state.update { it.copy(stock = demoStock(quote, leader)) }
            return
        }
        val end = stockHistoryEnd(quote,state.value.monitor.targetDate.ifBlank { state.value.date })
        stockJob = viewModelScope.launch {
            val result = try {
                api.stockDetail(quote, end, leader)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StockDetail(quote, loading = false, error = if (e is IllegalArgumentException || e is IllegalStateException) e.message else "行情读取失败，请检查网络后重试", leader = leader)
            }
            if(generation==stockGeneration) state.update { it.copy(stock = result) }
        }
    }

    fun retryStock() { state.value.stock?.let { openStock(it.quote, it.leader) } }

    fun select(board: Board) {
        memberJob?.cancel()
        val generation=++memberGeneration
        state.update { it.copy(selected = board, members = emptyList(), memberNote = "读取该日成分快照…") }
        refreshIntelligence()
        if (state.value.demo) {
            state.update {
                it.copy(
                    members = Demo.signals(state.value.rules).let { signals ->
                        val offset = Demo.boards.indexOfFirst { b -> b.id == board.id }.coerceAtLeast(0)
                        (signals.drop(offset) + signals.take(offset)).take(3).map { s -> s.quote }
                    },
                    memberNote = "模拟成分及走势 · 非真实板块关系",
                )
            }
            return
        }
        memberJob =
            viewModelScope.launch {
                try {
                    val date = board.date.ifBlank { state.value.monitor.targetDate.ifBlank { state.value.date } }.also { kotlinx.datetime.LocalDate.parse(it) }
                    val rows =
                        api.get(
                                "/v1/cn/boards/${board.id}/members",
                                mapOf("observed_date" to date, "limit" to "10"),
                            )
                            .also { require(it.str("trade_date")==date) { "成分快照日期不一致" } }
                            .rows()
                    var missing=0
                    val quotes =
                        rows.map { r ->
                            val id = "CN.stock.${r.str("symbol")}"
                            try {
                            val bars =
                                api.get("/v1/bars/$id", mapOf("end" to date, "limit" to "40"))
                                    .rows()
                            val last = bars.lastOrNull()
                            Quote(
                                id,
                                r.str("name"),
                                r.str("symbol"),
                                last?.num("close"),
                                last?.num("change_pct"),
                                last?.str("trade_date").orEmpty(),
                                last?.str("source").orEmpty(),
                                bars.mapNotNull { it.num("close") },
                            )
                            } catch(e: CancellationException) { throw e } catch(_: Exception) {
                                missing++
                                Quote(id,r.str("name"),r.str("symbol"),source="行情暂不可用")
                            }
                        }
                    if(generation==memberGeneration) state.update {
                        it.copy(
                            members = quotes,
                            memberNote =
                                if (rows.isEmpty()) "该日完整成分快照尚未取得；不使用其他分类替代"
                                else "$date · 成分快照前 10 项 · $missing 项行情读取失败；不是核心标的排名",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if(generation==memberGeneration) state.update { it.copy(memberNote = "成分行情读取失败，请重新选择板块") }
                }
            }
    }

    fun filter(r: Rules) {
        state.update {
            it.copy(rules = r, signals = candidates.filter { s -> Indicators.matches(s, r) })
        }
    }

    fun choosePreset(preset: StrategyPreset) {
        filter(state.value.rules.copy(preset = preset))
        scan()
    }

    fun setMonitorActive(value: Boolean) {
        if (active == value) return
        active = value
        monitoringJob?.cancel()
        if (!value) { intelligenceGeneration++; intelligencePending = false; intelligenceJob?.cancel(); state.update { it.copy(monitor = it.monitor.copy(loading = false)) }; return }
        if (state.value.monitor.settings.enabled) refreshIntelligence()
        monitoringJob = viewModelScope.launch {
            while (isActive) {
                delay(300000)
                if (state.value.monitor.settings.enabled) refreshIntelligence()
            }
        }
    }

    private fun changeMonitorSettings(transform: (MonitorSettings) -> MonitorSettings) {
        state.update { it.copy(monitor = it.monitor.copy(settings = transform(it.monitor.settings))) }
        if (!state.value.demo) runCatching { saveMonitorSettings(state.value.monitor.settings) }.onFailure {
            state.update { s -> s.copy(monitor = s.monitor.copy(note = "设置保存失败，本次会话仍有效")) }
        }
    }

    fun toggleMonitoring(enabled: Boolean) {
        changeMonitorSettings { it.copy(enabled = enabled) }
        if (enabled) refreshIntelligence()
        else { intelligenceGeneration++; intelligencePending = false; intelligenceJob?.cancel(); state.update { it.copy(monitor = it.monitor.copy(loading = false)) } }
    }

    fun setFlowDays(days: Int) { if (days in listOf(5,10,20)) changeMonitorSettings { it.copy(days = days) } }

    fun toggleWatch(board: Board) {
        val watched = state.value.monitor.settings.watched
        if (watched.none { it.id == board.id } && watched.size >= 12) {
            state.update { it.copy(monitor = it.monitor.copy(note = "最多同时跟踪 12 个板块")) }; return
        }
        changeMonitorSettings { it.copy(watched = if (watched.any { b -> b.id == board.id }) watched.filterNot { b -> b.id == board.id } else watched + board) }
        refreshIntelligence()
    }

    fun markEventRead(event: MarketEvent) { changeMonitorSettings { it.copy(readEvents = (it.readEvents + event.revision).toList().takeLast(500).toSet()) } }

    private fun changeWorkspace(transform: (WorkspaceSettings)->WorkspaceSettings): Boolean {
        state.update { it.copy(workspace=it.workspace.copy(settings=transform(it.workspace.settings),message="")) }
        return state.value.demo || runCatching { writeWorkspacePreferences(encodeWorkspace(state.value.workspace.settings)) }.onFailure {
            state.update { it.copy(workspace=it.workspace.copy(message="本地保存失败，本次会话内仍有效")) }
        }.isSuccess
    }

    fun addToPool(quote: Quote) {
        if(!eligibleForPool(quote,state.value.demo) || poolContains(state.value.workspace.settings.stocks,quote)) return
        changeWorkspace { it.copy(stocks=it.stocks+WatchedStock(normalizedPoolId(quote,state.value.demo),quote.name,quote.symbol)) }
        refreshIntelligence()
    }
    fun removeFromPool(id: String) {
        val removed=state.value.workspace.settings.stocks.find { it.id==id } ?: return
        changeWorkspace { it.copy(stocks=it.stocks.filterNot { stock -> stock.id==id }) }
        state.update { it.copy(workspace=it.workspace.copy(removedStock=removed)) }
    }
    fun undoRemove() {
        val removed=state.value.workspace.removedStock ?: return
        if(state.value.workspace.settings.stocks.none { it.symbol==removed.symbol }) changeWorkspace { it.copy(stocks=it.stocks+removed) }
        state.update { it.copy(workspace=it.workspace.copy(removedStock=null)) }
        refreshIntelligence()
    }
    fun pinStock(id: String) { changeWorkspace { it.copy(stocks=it.stocks.map { stock -> if(stock.id==id) stock.copy(pinned=!stock.pinned) else stock }) } }
    fun refreshPool() { membershipCache=null;refreshIntelligence() }
    fun saveAiConfiguration(config: AiConfiguration,key: String): Boolean {
        val clean=config.copy(baseUrl=config.baseUrl.trim().trimEnd('/'),model=config.model.trim())
        val error=validateAiConfiguration(clean)
        if(error!=null) { state.update { it.copy(workspace=it.workspace.copy(message=error)) };return false }
        val previous=state.value.workspace.settings.ai
        if(previous.baseUrl!=clean.baseUrl || previous.provider!=clean.provider) aiSessionKey=""
        if(key.isNotBlank()) aiSessionKey=key.trim()
        val saved=changeWorkspace { it.copy(ai=clean) }
        state.update { it.copy(workspace=it.workspace.copy(aiKeyPresent=aiSessionKey.isNotBlank(),message=if(!saved) "本地保存失败，本次会话内仍有效" else if(it.demo) "演示配置仅本次会话有效；分析未启用，未发送请求" else "地址和模型配置已保存；密钥仅在内存，分析未启用")) }
        return saved
    }
    fun clearAiKey() { aiSessionKey="";state.update { it.copy(workspace=it.workspace.copy(aiKeyPresent=false,message="本次会话密钥已清除")) } }

    fun refreshIntelligence() {
        if (state.value.monitor.loading) { intelligencePending = true; return }
        val snapshot = state.value
        var tracked = (snapshot.monitor.settings.watched + listOfNotNull(snapshot.selected)).distinctBy { it.id }
        if (snapshot.demo) {
            val context=demoWatchContext(snapshot.workspace.settings.stocks)
            tracked=(tracked+context.associations.values.flatten()).distinctBy { it.id }
            state.update { it.copy(workspace=it.workspace.copy(context=context),monitor = it.monitor.copy(flows = tracked.associate { b -> b.id to demoFlows(b.id) },feed = demoEvents(),checkedAt = kotlinx.datetime.Clock.System.now().toString())) }
            return
        }
        if (api.token.isBlank()) return
        val generation = ++intelligenceGeneration
        intelligenceJob = viewModelScope.launch {
            state.update { it.copy(monitor = it.monitor.copy(loading = true)) }
            try {
                val targetDate = try {
                    api.get("/v1/cn/rankings",mapOf("limit" to "1","classification_source" to "ths")).str("trade_date")
                        .also { kotlinx.datetime.LocalDate.parse(it) }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
                val feed = try { api.events() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    snapshot.monitor.feed.copy(error = if (e is IllegalStateException || e is IllegalArgumentException) e.message else "事件更新失败")
                }
                val context = if(snapshot.workspace.settings.stocks.isEmpty()) WatchContext() else try {
                    require(targetDate.isNotBlank())
                    val index=membershipCache?.takeIf { it.date==targetDate } ?: api.membershipIndex(targetDate).also { if(it.complete) membershipCache=it }
                    api.watchContext(snapshot.workspace.settings.stocks,index)
                } catch(e: CancellationException) { throw e } catch(_: Exception) {
                    WatchContext(note="观察池更新失败：请检查连接与当日成分采集，点击刷新重试")
                }
                tracked=(tracked+context.associations.values.flatten()).distinctBy { it.id }
                val flows = snapshot.monitor.flows.toMutableMap()
                for (board in tracked) {
                    flows[board.id] = try { api.flows(board.id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        (flows[board.id] ?: FlowSeries(board.id,emptyList(),emptyList(),"","","")).copy(error = if (e is IllegalStateException || e is IllegalArgumentException) e.message else "资金更新失败")
                    }
                }
                if (generation == intelligenceGeneration) state.update { it.copy(workspace=it.workspace.copy(context=context),monitor = it.monitor.copy(feed = feed, flows = flows, targetDate = targetDate, checkedAt = kotlinx.datetime.Clock.System.now().toString(),note = if (targetDate.isBlank()) "目标交易日读取失败，资金提醒暂停，仍可查阅历史" else "每 5 分钟检查关注板块与重点事件")) }
            } finally {
                if (generation == intelligenceGeneration) {
                    state.update { it.copy(monitor = it.monitor.copy(loading = false)) }
                    if (intelligencePending) { intelligencePending = false; refreshIntelligence() }
                }
            }
        }
    }

    fun cancelScan() {
        scanGeneration++
        scan?.cancel()
        state.update { it.copy(scanning=false,scanPhase=ScanPhase.STOPPED,scanNote="扫描已停止 · 当前结果仅来自已处理标的，非完整结果") }
    }

    fun scan() {
        if(state.value.demo) {
            candidates=Demo.signals(state.value.rules)
            filter(state.value.rules)
            state.update { it.copy(scanPhase=ScanPhase.COMPLETE) }
            return
        }
        if(api.token.isBlank()) { state.update { it.copy(error="请先连接数据服务或开启演示") };return }
        scan?.cancel()
        val generation=++scanGeneration
        val rules=state.value.rules
        candidates=emptyList()
        state.update { it.copy(scanning=true,scanPhase=ScanPhase.RUNNING,error=null,signals=emptyList(),scanProgress=null,scanNote="正在分页读取全量 A 股目录…") }
        scan=viewModelScope.launch {
            try {
                api.scanUniverse(rules) { progress,results ->
                    if(generation==scanGeneration) {
                        candidates=results
                        state.update { it.copy(signals=results.filter { s -> Indicators.matches(s,rules) },scanProgress=progress,
                            scanNote="${progress.date} · ${progress.processed} / ${progress.total} · 有效 ${progress.valid} · 缺失或失败 ${progress.excluded}") }
                    }
                }
                if(generation==scanGeneration) state.update { it.copy(scanPhase=ScanPhase.COMPLETE,scanNote=it.scanNote+" · 全部目录扫描完成；完整市场覆盖取决于后端目录与采集质量") }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(generation==scanGeneration) state.update { it.copy(scanPhase=ScanPhase.FAILED,error=if(e is ApiFailure) e.message else "全量目录或行情读取失败，请重试",scanNote=it.scanNote+" · 扫描未完成，结果仅供部分范围查看") } }
            finally { if(generation==scanGeneration) state.update { it.copy(scanning=false) } }
        }
    }
}

object Demo {
    val boards =
        listOf(
            Board("demo1", "半导体", 3.42),
            Board("demo2", "通信设备", 2.86),
            Board("demo3", "电力设备", 1.93),
            Board("demo4", "创新药", 1.28),
            Board("demo5", "银行", -0.62),
            Board("demo6", "煤炭", -1.35),
        )
    val leaders =
        listOf(
            Leader("中芯国际", "688981", 382000000.0, "演示 · 日涨幅偏离值"),
            Leader("中际旭创", "300308", 216000000.0, "演示 · 换手率"),
            Leader("东方财富", "300059", -97000000.0, "演示 · 日涨幅偏离值"),
        )
    val us =
        listOf(
                Quote("demoNVDA", "英伟达", "NVDA", 142.87, 2.34),
                Quote("demoAAPL", "苹果", "AAPL", 228.12, -0.58),
                Quote("demoMSFT", "微软", "MSFT", 428.76, 1.12),
            )
            .map { demoStock(it).snapshotQuote() }

    fun signals(rules: Rules) = (0..5).mapNotNull { i ->
        val quote = Quote(
            "demo$i",
            listOf("中芯国际", "中际旭创", "宁德时代", "招商银行", "东方财富", "比亚迪")[i],
            listOf("688981", "300308", "300750", "600036", "300059", "002594")[i],
        )
        val detail = demoStock(quote)
        Indicators.calculate(
            detail.snapshotQuote(),
            detail.candles.map { Bar(it.date, it.close, it.high, it.volume, it.source, it.open, it.low, it.volumeUnit) },
            rules,
        )
    }
}
