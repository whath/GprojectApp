package cn.gproject

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

@Composable
internal expect fun DetailBackHandler(enabled: Boolean, onBack: () -> Unit)

@Composable
internal fun DetailHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Surface(color = Paper) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, fontSize = 11.sp, color = Muted)
                }
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable
internal fun BoardDetailPage(state: ScreenState, wide: Boolean, model: MarketViewModel) {
    val boardDate = state.selected?.date?.ifBlank { state.date } ?: state.date
    Column(Modifier.fillMaxSize().background(Paper).testTag("board-detail")) {
        DetailHeader("板块详情", if (state.demo) "演示模式 · 模拟板块及成分关系" else "$boardDate · 同花顺行业") { model.back() }
        Row(Modifier.weight(1f)) {
            if (wide) {
                LazyColumn(Modifier.width(232.dp).fillMaxHeight().background(Color.White).testTag("board-switch-left"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("板块列表", Modifier.padding(bottom = 12.dp), color = Muted, fontSize = 12.sp) }
                    items(state.boards, key = { it.id }) { board ->
                        Surface(onClick = { model.select(board) }, color = if (state.selected?.id == board.id) Ink else Paper, shape = RoundedCornerShape(10.dp), modifier = Modifier.testTag("switch-${board.id}")) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(board.name, modifier = Modifier.weight(1f).padding(end = 8.dp), color = if (state.selected?.id == board.id) Color.White else Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pct(board.change), color = if (state.selected?.id == board.id) Color.White else color(board.change), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                if (!wide) LazyRow(Modifier.fillMaxWidth().testTag("board-switch-top"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.boards, key = { it.id }) { board ->
                        FilterChip(state.selected?.id == board.id, { model.select(board) }, label = { Text(board.name) }, modifier = Modifier.testTag("switch-${board.id}"))
                    }
                }
                key(state.selected?.id) {
                    LazyColumn(Modifier.fillMaxSize().testTag("board-scroll"), contentPadding = PaddingValues(if (wide) 28.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            Panel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(state.selected?.name ?: "选择板块", fontSize = if (wide) 28.sp else 23.sp, fontWeight = FontWeight.Bold)
                                        Caption("同花顺行业 · $boardDate")
                                    }
                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(pct(state.selected?.change), color = color(state.selected?.change), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                                        Caption("收盘涨跌幅")
                                    }
                                }
                                if (state.selected?.change == null) Caption("本次未取得板块涨跌幅，暂无法判断价格强弱。")
                                Caption("先核对资金持续性，再查看成分股；涨幅与净流入分别判断。")
                            }
                        }
                        item { FlowPanel(state, model) }
                        item {
                            Panel {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("板块成分", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    TextButton(onClick = { state.selected?.let(model::select) }) { Text("刷新成分") }
                                }
                                Caption(state.memberNote)
                                Caption("已加载 ${state.members.size} 只 · 成分样本，不能代表全板块涨跌分布")
                                if (state.memberNote.contains("读取该日")) LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (state.members.isEmpty() && !state.memberNote.contains("读取该日")) Empty("暂无成分行情", "可切换其他板块，或重新加载当前板块。")
                                state.members.forEach { quote ->
                                    QuoteRow(quote) { model.openStock(quote) }
                                    HorizontalDivider(color = Line)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun LeadersPage(state: ScreenState, onBack: () -> Unit, onStock: (Leader) -> Unit) {
    Column(Modifier.fillMaxSize().background(Paper)) {
        DetailHeader("龙虎榜", "${state.leaderDate.ifBlank { "披露日期待更新" }} · ${state.leaders.size} 条已加载披露") { onBack() }
        LazyColumn(Modifier.fillMaxSize().testTag("leaders-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Caption(if (state.demo) "演示数据 · 点击个股查看行情与 K 线" else "逐条披露，不同原因及单日／多日榜不合并。点击个股查看行情。") }
            if (state.leaders.isEmpty()) item { Panel { Empty("暂无龙虎榜披露", "请连接服务或开启演示模式。") } }
            items(state.leaders) { leader ->
                Surface(onClick = { onStock(leader) }, shape = shape, color = Color.White, modifier = Modifier.fillMaxWidth().testTag("leader-${leader.symbol}")) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(leader.name, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(money(leader.net), color = color(leader.net), fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
                        }
                        Caption("${leader.symbol} · ${leader.reason}")
                        Caption("净买额 · ${leader.date.ifBlank { state.leaderDate.ifBlank { "日期待更新" } }} · 点击查看个股详情")
                    }
                }
            }
        }
    }
}

internal fun quantity(value: Double?, unit: String = ""): String = when {
    value == null -> "—"
    abs(value) >= 100000000 -> decimal(value / 100000000) + "亿" + unit
    abs(value) >= 10000 -> decimal(value / 10000) + "万" + unit
    else -> decimal(value) + unit
}

internal fun volumeLabel(unit: String) = when (unit) { "lot_100_shares" -> "手"; "share" -> "股"; else -> "（原始单位）" }

@Composable
internal fun StockDetailPage(stock: StockDetail, demo: Boolean, wide: Boolean, onBack: () -> Unit, onRetry: () -> Unit, canAddToPool:Boolean=false, inPool:Boolean=false,onAddToPool:()->Unit={}, analysisParameters: StrategyParameters = StrategyParameters()) {
    var period by remember(stock.quote.id) { mutableStateOf(ChartPeriod.DAY) }
    var focusChart by remember(stock.quote.id) { mutableStateOf(false) }
    val latest = stock.candles.lastOrNull()
    val previous = stock.candles.dropLast(1).lastOrNull()
    val currency = stock.currency
    val review = remember(stock.candles, analysisParameters) { reviewDetail(stock.candles, analysisParameters) }
    Column(Modifier.fillMaxSize().background(Paper).testTag("stock-detail")) {
        DetailHeader(stock.quote.name, "${stock.quote.symbol} · ${if (demo) "演示模式 · 非真实行情" else "历史收盘行情"}", onBack)
        LazyColumn(Modifier.fillMaxSize().testTag("stock-scroll"), contentPadding = PaddingValues(if (wide) 28.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!focusChart) item(key="stock-quote") {
                Panel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Caption("收盘价 · $currency")
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(decimal(latest?.close), color = color(latest?.change), fontSize = if (wide) 36.sp else 32.sp, fontWeight = FontWeight.Bold)
                                Text(pct(latest?.change), color = color(latest?.change), fontSize = 18.sp, modifier = Modifier.padding(bottom = 5.dp))
                            }
                        }
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                            Metric("最高 / 最低", "${decimal(latest?.high)} / ${decimal(latest?.low)}")
                            Metric("成交量", quantity(latest?.volume, volumeLabel(latest?.volumeUnit.orEmpty())))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Caption(if (latest == null) "尚无可用行情" else "截至 ${latest.date} · 不复权") }
                        if(canAddToPool) TextButton(onClick=onAddToPool,enabled=!inPool,modifier=Modifier.testTag("detail-add-pool")) { Text(if(inPool) "已在观察池" else "加入观察池") }
                    }
                    if (latest != null && latest.change == null) Caption("涨跌幅未提供；当前不以相邻已观测收盘价代替前收盘计算。")
                    if (!demo) detailDateNote(stock.quote.date, latest?.date)?.let { Caption(it) }
                    if (stock.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    stock.error?.let { error ->
                        Text(error, color = Rise, fontSize = 13.sp)
                        TextButton(onClick = onRetry) { Text("重试行情") }
                    }
                }
            }
            item(key="stock-chart") {
                Panel(spacing=8.dp) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("行情走势",fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                            if (focusChart) Caption("${stock.quote.symbol} · ${latest?.date ?: "行情待更新"} · ${decimal(latest?.close)} $currency")
                        }
                        Text("不复权 · ${if(demo) "模拟" else "收盘"}",fontSize=11.sp,color=Muted)
                        IconToggleButton(checked=focusChart,onCheckedChange={focusChart=it},modifier=Modifier.size(40.dp).testTag("chart-focus")) {
                            Icon(if(focusChart) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,if(focusChart) "退出专注" else "专注图表",Modifier.size(20.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(10.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChartPeriod.entries.forEach { entry ->
                            Surface(onClick={period=entry},color=if(period==entry) Ink else Color.Transparent,shape=RoundedCornerShape(7.dp),modifier=Modifier.weight(1f).testTag("period-${entry.name}")) {
                                Box(Modifier.height(40.dp),contentAlignment=Alignment.Center) { Text(entry.label,color=if(period==entry) Color.White else Muted,fontSize=13.sp,fontWeight=if(period==entry) FontWeight.SemiBold else FontWeight.Normal) }
                            }
                        }
                    }
                    if (period == ChartPeriod.INTRADAY) {
                        if (stock.minutes.isEmpty()) Empty("分时行情待接入", "当前服务仅提供日线，暂无分钟行情。可切换日K、周K、月K查看。")
                        else IntradayChart(stock.minutes)
                    } else if (stock.candles.isEmpty()) {
                        Empty(if (stock.loading) "正在加载 K 线" else "暂无可用 K 线", "需要完整的开盘、最高、最低、收盘价；缺失数据不补造。")
                    } else key(stock.quote.id, period) {
                        CandlestickChart(remember(stock.candles, period) { aggregateCandles(stock.candles, period) }, wide,period,stock.candles,stock.funds)
                    }
                    Caption(if (demo) "模拟行情，仅用于体验；模拟日历未扣除交易所节假日。" else "${latest?.source?.ifBlank { "来源待确认" } ?: "来源待确认"} · ${stock.candles.size} 根已观测日线 · 非实时行情")
                    if (period == ChartPeriod.WEEK || period == ChartPeriod.MONTH) Caption("按自然周（月）汇总已观测日线；首尾周期可能不完整，缺失交易日不补齐。")
                    if (focusChart && stock.error != null) {
                        Text(stock.error, color = Rise, fontSize = 12.sp)
                        TextButton(onClick = onRetry) { Text("重试行情") }
                    }
                }
            }
            if (!focusChart) item(key="stock-review") {
                DetailReviewPanel(review, stock, analysisParameters)
            }
            if (!focusChart) stock.leader?.let { leader -> item(key="stock-disclosure") {
                Panel {
                    Text("本次龙虎榜披露", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("净买额 ${money(leader.net)}", color = color(leader.net), fontSize = 20.sp)
                        Caption("${leader.date.ifBlank { "日期待确认" }}")
                    }
                    Text(leader.reason, fontSize = 13.sp)
                    Caption("单条披露：不合并其他上榜原因或统计周期；净买额不代表该股全市场净流入。")
                    if (leader.date.isNotBlank() && latest != null && leader.date != latest.date) Caption("披露日期与当前 K 线日期不同，请分别核对。")
                }
            } }
            if (!focusChart) item(key="stock-metrics") {
                Panel {
                    Text("常规行情", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    val fields = listOf(
                        "开盘" to decimal(latest?.open), "最高" to decimal(latest?.high), "最低" to decimal(latest?.low),
                        "前次收盘" to decimal(previous?.close), "成交量" to quantity(latest?.volume, volumeLabel(latest?.volumeUnit.orEmpty())), "成交额" to quantity(latest?.amount),
                    )
                    fields.chunked(if (wide) 6 else 2).forEach { group ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            group.forEach { (label, value) -> Box(Modifier.weight(1f).padding(vertical = 8.dp)) { Metric(label, value) } }
                            repeat((if (wide) 6 else 2) - group.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Caption("价格及成交额币种：$currency。前次收盘为上一条已观测日线；换手率、总市值、市盈率接口待接入。")
                }
            }
        }
    }
}

@Composable
private fun DetailReviewPanel(review: DetailReview, stock: StockDetail, parameters: StrategyParameters) {
    var showEvidence by remember(stock.quote.id, parameters) { mutableStateOf(false) }
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("结构与风险复核", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Caption("日线")
        }
        Caption("基于 ${stock.candles.lastOrNull()?.date ?: "待更新"} 收盘 · 与雷达共用技术参数")
        when {
            stock.loading -> Caption("行情正在更新，结构判断待数据加载完成。")
            stock.error != null -> Caption("行情更新失败，暂不刷新结构判断；请先重试行情。")
            !review.ready -> Empty("数据不足，暂不判断", "需足够长的完整日线、成交量及一致的来源与单位；不能据此认定没有风险。")
            review.setups.isEmpty() -> Caption("当前参数未识别出特定结构；不代表趋势安全或后续不会变化。")
            else -> review.setups.forEach { setup ->
                val risk = setup.preset == StrategyPreset.TOP_RISK || setup.preset == StrategyPreset.DOWNTREND
                Surface(color = if (risk) Rise.copy(alpha = .06f) else Paper, shape = RoundedCornerShape(10.dp), modifier = Modifier.testTag("detail-setup-${setup.preset.name}")) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(setup.preset.title, color = if (risk) Rise else Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(setup.status, fontSize = 12.sp, color = if (risk) Rise else Muted)
                        HorizontalDivider(color = Line)
                        Text("失效条件：${setup.invalidation}", color = Ink, fontSize = 12.sp)
                        Text("下一步确认：${setup.confirmation}", color = Muted, fontSize = 12.sp)
                        if (showEvidence) setup.evidence.forEach { Caption("• $it") }
                    }
                }
            }
        }
        if (review.ready && !stock.loading && stock.error == null && review.setups.isNotEmpty()) {
            TextButton(onClick = { showEvidence = !showEvidence }, modifier = Modifier.testTag("detail-evidence")) { Text(if (showEvidence) "收起技术依据" else "查看技术依据") }
        }
        Caption("MA${parameters.fast} / MA${parameters.slow} · 当前展示全部结构类型。周／月切换只改变图表周期，不改变日线判断。")
        Caption("不复权价格遇除权除息可能产生跳空，需核对公司行动后再解读结构。")
    }
}

@Composable
private fun IntradayChart(points: List<MinutePoint>) {
    var selected by remember(points) { mutableStateOf(points.lastIndex) }
    val point = points[selected]
    Text("${point.time}  ${decimal(point.price)}", fontSize = 13.sp, color = Ink)
    val (low,high) = chartPriceBounds(points.map { it.price })
    ChartScale(high, low) {
        Canvas(Modifier.fillMaxWidth().height(230.dp).testTag("intraday-chart").pointerInput(points) {
            detectTapGestures { selected = (it.x / size.width * points.lastIndex).roundToInt().coerceIn(points.indices) }
        }) {
            val span = (high - low).coerceAtLeast(.01)
            repeat(5) { row -> val y = size.height * row / 4; drawLine(Line, Offset(0f,y), Offset(size.width,y)) }
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = size.width * i / points.lastIndex.coerceAtLeast(1)
                val y = (size.height * (high-p.price) / span).toFloat()
                if (i == 0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            drawPath(path, Ink, style = Stroke(2.dp.toPx()))
            val x = selected.toFloat() / points.lastIndex.coerceAtLeast(1) * size.width
            drawLine(Muted, Offset(x,0f), Offset(x,size.height), strokeWidth = 1.dp.toPx())
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Caption("09:30"); Caption("11:30 / 13:00"); Caption("15:00")
    }
    Caption("分时演示 · 点击曲线查看对应时刻价格")
}

@Composable
private fun ChartScale(high: Double, low: Double, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(46.dp).height(230.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Caption(decimal(high)); Caption(decimal((high+low)/2)); Caption(decimal(low))
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

