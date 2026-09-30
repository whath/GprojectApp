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

data class Board(val id: String, val name: String, val change: Double?)

data class Leader(val name: String, val symbol: String, val net: Double?, val reason: String)

data class Bar(
    val date: String,
    val close: Double,
    val high: Double,
    val volume: Double?,
    val source: String,
)

data class Rules(
    val ma: Int = 20,
    val aboveMa: Boolean = true,
    val rsiMin: Int = 40,
    val rsiMax: Int = 75,
    val volume: Double = 1.0,
    val breakout: Boolean = false,
)

data class Signal(
    val quote: Quote,
    val ma: Double,
    val rsi: Double,
    val volume: Double,
    val breakout: Boolean,
)

data class ScreenState(
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
    val scanNote: String = "筛选范围：已登记 A 股前 30 只；不会代表全市场。",
)

object Indicators {
    // Wilder RSI; all windows use observed daily bars, never silently invent missing sessions.
    fun calculate(q: Quote, bars: List<Bar>, rules: Rules): Signal? {
        if (
            bars.size < max(21, rules.ma) ||
                bars.any {
                    !it.close.isFinite() ||
                        it.close <= 0 ||
                        !it.high.isFinite() ||
                        it.high < it.close
                } ||
                bars.map { it.source }.distinct().size != 1
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
        )
    }

    fun matches(s: Signal, r: Rules) =
        (!r.aboveMa || s.quote.price!! > s.ma) &&
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
        if (response.status.value == 401) error("令牌无效或已过期，请重新连接")
        if (response.status.value !in 200..299) error("服务请求失败（${response.status.value}），请稍后重试")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    override fun close() {
        token = ""
        client.close()
    }
}

class MarketViewModel : ViewModel() {
    private val api = MarketRepository()
    private val state = MutableStateFlow(ScreenState())
    val ui = state.asStateFlow()
    private var request: Job? = null
    private var scan: Job? = null
    private var memberJob: Job? = null
    private var candidates = emptyList<Signal>()

    fun close() {
        request?.cancel()
        scan?.cancel()
        memberJob?.cancel()
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
        request?.cancel()
        scan?.cancel()
        memberJob?.cancel()
        candidates = emptyList()
        api.base = base.trim()
        api.token = token.trim()
        state.value = ScreenState(loading = true, rules = state.value.rules)
        refresh()
    }

    fun demo() {
        request?.cancel()
        scan?.cancel()
        memberJob?.cancel()
        api.token = ""
        candidates = Demo.signals(Rules())
        state.value =
            ScreenState(
                demo = true,
                date = "演示快照",
                boards = Demo.boards,
                leaders = Demo.leaders,
                us = Demo.us,
                rules = Rules(),
                note = "演示数据 · 仅用于体验界面，不是实际行情",
                scanNote = "演示观察池 · 6 个模拟标的",
            )
        filter(Rules())
    }

    fun refresh() {
        if (state.value.demo) {
            scan()
            return
        }
        if (api.token.isBlank()) return
        request?.cancel()
        request =
            viewModelScope.launch {
                memberJob?.cancel()
                state.update {
                    it.copy(
                        loading = true,
                        error = null,
                        boards = emptyList(),
                        leaders = emptyList(),
                        us = emptyList(),
                        selected = null,
                        members = emptyList(),
                    )
                }
                try {
                    val boards =
                        api.get(
                            "/v1/cn/rankings",
                            mapOf("limit" to "50", "classification_source" to "ths"),
                        )
                    val date = boards.str("trade_date")
                    state.update {
                        it.copy(
                            date = date,
                            boards =
                                boards.rows().map { r ->
                                    Board(r.str("id"), r.str("name"), r.num("price_change_pct"))
                                },
                            note = "同花顺行业 · 已采集范围前 50 · 不复权收盘价变化",
                        )
                    }
                    val lhb = api.get("/v1/cn/lhb", mapOf("trade_date" to date, "limit" to "50"))
                    state.update {
                        it.copy(
                            leaders =
                                lhb.rows().map { r ->
                                    Leader(
                                        r.str("name"),
                                        r.str("symbol"),
                                        r.num("net_amount"),
                                        r.str("reason"),
                                    )
                                }
                        )
                    }
                    val us = api.get("/v1/us/ai")
                    state.update {
                        it.copy(
                            us =
                                us.rows().map { r ->
                                    Quote(
                                        "US.stock.${r.str("symbol")}",
                                        r.str("group").ifBlank { r.str("symbol") },
                                        r.str("symbol"),
                                        r.num("close"),
                                        r.num("change_1d_pct"),
                                        r.str("trade_date"),
                                        r.str("source"),
                                    )
                                }
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.update {
                        it.copy(
                            error =
                                if (e is IllegalStateException) e.message else "无法连接服务，请检查地址、网络与证书"
                        )
                    }
                } finally {
                    state.update { it.copy(loading = false) }
                }
            }
    }

    fun select(board: Board) {
        memberJob?.cancel()
        state.update { it.copy(selected = board, members = emptyList(), memberNote = "读取该日成分快照…") }
        if (state.value.demo) {
            state.update {
                it.copy(
                    members = Demo.signals(state.value.rules).take(3).map { s -> s.quote },
                    memberNote = "模拟成分及走势 · 非真实板块关系",
                )
            }
            return
        }
        memberJob =
            viewModelScope.launch {
                try {
                    val date = state.value.date
                    val rows =
                        api.get(
                                "/v1/cn/boards/${board.id}/members",
                                mapOf("observed_date" to date, "limit" to "10"),
                            )
                            .rows()
                    val quotes =
                        rows.map { r ->
                            val id = "CN.stock.${r.str("symbol")}"
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
                        }
                    state.update {
                        it.copy(
                            members = quotes,
                            memberNote =
                                if (rows.isEmpty()) "该日完整成分快照尚未取得；不使用其他分类替代"
                                else "当前成分快照前 10 项 · 不是历史成分或核心标的排名",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state.update { it.copy(memberNote = "成分行情读取失败，请重新选择板块") }
                }
            }
    }

    fun filter(r: Rules) {
        state.update {
            it.copy(rules = r, signals = candidates.filter { s -> Indicators.matches(s, r) })
        }
    }

    fun scan() {
        if (state.value.demo) {
            candidates = Demo.signals(state.value.rules)
            filter(state.value.rules)
            return
        }
        if (api.token.isBlank()) {
            state.update { it.copy(error = "请先连接数据服务或开启演示") }
            return
        }
        scan?.cancel()
        scan =
            viewModelScope.launch {
                state.update { it.copy(scanning = true, error = null, signals = emptyList()) }
                candidates = emptyList()
                try {
                    val date = api.get("/v1/cn/rankings", mapOf("limit" to "1")).str("trade_date")
                    val instruments =
                        api.get(
                                "/v1/instruments",
                                mapOf("market" to "CN", "kind" to "stock", "limit" to "30"),
                            )
                            .rows()
                    val results = mutableListOf<Signal>()
                    var excluded = 0
                    for ((index, r) in instruments.withIndex()) {
                        state.update { it.copy(scanNote = "正在计算 ${index+1} / ${instruments.size}") }
                        try {
                            val bars =
                                api.get(
                                        "/v1/bars/${r.str("id")}",
                                        mapOf("end" to date, "limit" to "100"),
                                    )
                                    .rows()
                                    .mapNotNull { b ->
                                        val close = b.num("close")
                                        val high = b.num("high")
                                        if (close == null || high == null) null
                                        else
                                            Bar(
                                                b.str("trade_date"),
                                                close,
                                                high,
                                                b.num("volume"),
                                                b.str("source"),
                                            )
                                    }
                            val signal =
                                if (bars.lastOrNull()?.date == date)
                                    Indicators.calculate(
                                        Quote(r.str("id"), r.str("name"), r.str("symbol")),
                                        bars,
                                        state.value.rules,
                                    )
                                else null
                            if (signal != null) results.add(signal) else excluded++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            excluded++
                        }
                    }
                    candidates = results
                    filter(state.value.rules)
                    state.update {
                        it.copy(
                            scanNote =
                                "$date · 前 ${instruments.size} 只 / 有效 ${results.size} / 缺失或失败 $excluded；按实际观测日线计算，不是全市场扫描。"
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state.update { it.copy(error = "筛选读取失败，请重试") }
                } finally {
                    state.update { it.copy(scanning = false) }
                }
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
            .mapIndexed { i, q ->
                q.copy(
                    date = "演示",
                    source = "模拟",
                    trend = List(36) { 100.0 + it * .24 + sin(it * .7 + i) * 2 },
                )
            }

    fun signals(rules: Rules) =
        (0..5).mapNotNull { i ->
            val bars =
                (0..99).map { day ->
                    val price = 60.0 + i * 12 + day * .12 + sin(day * .55 + i) * 2.5
                    Bar(
                        "demo-${day.toString().padStart(3,'0')}",
                        price,
                        price + .4,
                        if (day == 99) 150.0 + i * 20 else 100.0,
                        "模拟",
                    )
                }
            Indicators.calculate(
                    Quote(
                        "demo$i",
                        listOf("中芯国际", "中际旭创", "宁德时代", "招商银行", "东方财富", "比亚迪")[i],
                        listOf("688981", "300308", "300750", "600036", "300059", "002594")[i],
                        change = 1.2 + i * .3,
                    ),
                    bars,
                    rules,
                )
                ?.let { it.copy(quote = it.quote.copy(date = "演示")) }
        }
}
