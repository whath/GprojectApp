package cn.gproject

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

internal val Ink = Color(0xFF173D35)
internal val Muted = Color(0xFF65736F)
internal val Paper = Color(0xFFF4F6F7)
internal val Rise = Color(0xFFC4514C)
internal val Fall = Color(0xFF28836D)
internal val Line = Color(0xFFE5EAEC)
internal val shape = RoundedCornerShape(14.dp)

internal fun color(value: Double?) =
    if (value == null || value == 0.0) Muted else if (value > 0) Rise else Fall

internal fun decimal(n: Double?, digits: Int = 2): String {
    if (n == null || !n.isFinite()) return "—"
    val scale = 10.0.pow(digits)
    val rounded = round(abs(n) * scale).toLong()
    return (if (n < 0) "−" else "") +
        (rounded / scale.toLong()).toString() +
        if (digits > 0) "." + (rounded % scale.toLong()).toString().padStart(digits, '0') else ""
}

internal fun pct(n: Double?) = if (n == null) "—" else (if (n > 0) "+" else "") + decimal(n) + "%"

internal fun money(n: Double?) =
    if (n == null) "—" else (if (n > 0) "+" else "") + decimal(n / 100000000) + " 亿"

@Composable
fun App(model: MarketViewModel) {
    val state by model.ui.collectAsState()
    MonitorLifecycle(model::setMonitorActive)
    var tab by rememberSaveable { mutableStateOf(0) }
    val pageScroll = List(4) { rememberLazyListState() }
    var settings by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var leadersPage by remember { mutableStateOf(false) }
    var aiSettings by remember { mutableStateOf(false) }
    var signalSearch by rememberSaveable { mutableStateOf("") }
    var poolSearch by rememberSaveable { mutableStateOf("") }
    val displayedSignals=remember(state.signals,signalSearch) { state.signals.filter { matchesSearch(it.quote.name,it.quote.symbol,signalSearch) } }
    LaunchedEffect(state.sessionId) { leadersPage=false;signalSearch="";poolSearch="" }
    DetailBackHandler(state.stock != null || state.boardDetail || leadersPage) {
        if (!model.back()) leadersPage = false
    }
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Ink,
                secondary = Fall,
                background = Paper,
                surface = Color.White,
                onSurface = Ink,
                onBackground = Ink,
                outline = Line,
                primaryContainer = Color(0xFFE0E8DD),
                onPrimaryContainer = Ink,
                secondaryContainer = Color(0xFFE0E8DD),
                onSecondaryContainer = Ink,
                surfaceVariant = Color(0xFFE8ECE4),
                surfaceContainerHigh = Color(0xFFF3F4EE),
                surfaceContainerHighest = Line,
                outlineVariant = Line,
            ),
        typography = Typography(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Paper).safeDrawingPadding()) {
            val wide = maxWidth >= 900.dp
            if (state.stock != null) {
                StockDetailPage(state.stock!!, state.demo, wide, { model.back() }, model::retryStock,
                    eligibleForPool(state.stock!!.quote,state.demo),poolContains(state.workspace.settings.stocks,state.stock!!.quote),{ model.addToPool(state.stock!!.quote) },analysisParameters=state.rules.parameters)
            } else if (state.boardDetail) {
                BoardDetailPage(state, wide, model)
            } else if (leadersPage) {
                LeadersPage(state, { leadersPage = false }, model::openLeader)
            } else Row(Modifier.fillMaxSize()) {
                if (wide)
                    Column(
                        Modifier.width(200.dp)
                            .fillMaxHeight()
                            .background(Color(0xFFEDF2F0))
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Brand()
                        Spacer(Modifier.height(24.dp))
                        tabs.forEachIndexed { i, label ->
                            Surface(
                                onClick = { tab = i },
                                color = if (tab == i) Ink else Color.Transparent,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        tabIcon(i),
                                        null,
                                        tint = if (tab == i) Color.White else Muted,
                                    )
                                    Text(label, color = if (tab == i) Color.White else Ink)
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "LESS NOISE.\nMORE SIGNAL.",
                            fontSize = 11.sp,
                            color = Muted,
                            lineHeight = 20.sp,
                            letterSpacing = 2.sp,
                        )
                        Text("收盘之后，看清市场", fontSize = 12.sp, color = Muted)
                    }
                Scaffold(
                    containerColor = Paper,
                    modifier = Modifier.weight(1f),
                    topBar = { WorkspaceToolbar(state, wide, model::refresh) { settings = true } },
                    bottomBar = {
                        if (!wide)
                            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                                tabs.forEachIndexed { i, label ->
                                    NavigationBarItem(
                                        selected = tab == i,
                                        onClick = { tab = i },
                                        icon = { Icon(tabIcon(i), null, Modifier.size(22.dp)) },
                                        label = { Text(label, fontSize = 11.sp, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal) },
                                    )
                                }
                            }
                    },
                ) { padding ->
                    LazyColumn(
                        Modifier.fillMaxSize().padding(padding).testTag("page-scroll"),
                        state = pageScroll[tab],
                        contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 16.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(tabs[tab], fontSize = if (wide) 28.sp else 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                                    Text(listOf("先核对风险，再看板块强弱与观察清单", "先看关联事件，再核对各市场收盘表现", "选择组合 → 核验 K 线 → 加入观察", "优先复核置顶标的、板块走势与资金变化")[tab], fontSize = 12.sp, color = Muted)
                                }
                                Surface(color = Ink.copy(alpha = .06f), shape = RoundedCornerShape(8.dp)) {
                                    Text("收盘观察", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 11.sp, color = Ink)
                                }
                            }
                        }
                        item { ConnectionBanner(state, wide, { settings = true }, model::demo) }
                        if (tab == 0) item { MonitoringHighlights(state, model) }
                        if (tab == 0 && state.boards.isNotEmpty()) item { Overview(state) }
                        if (state.loading || state.scanning)
                            item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Fall) }
                        state.error?.let { error ->
                            item {
                                Panel {
                                    Text(error, color = Rise, fontSize = 13.sp)
                                    TextButton(onClick = { settings = true }) { Text("检查连接设置") }
                                }
                            }
                        }
                        if(state.sectionErrors.isNotEmpty()) item {
                            Panel {
                                Text("部分数据更新失败",fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Rise)
                                state.sectionErrors.forEach { (section,message) -> Caption("$section：$message") }
                                Caption("下方保留上次成功快照，请以各区域显示的日期为准。")
                                TextButton(onClick=model::refresh,enabled=!state.loading) { Text("重试行情") }
                            }
                        }
                        when (tab) {
                            0 -> {
                                item {
                                    AdaptivePair(wide,
                                        first = { BoardPanel(state, model::openBoard) },
                                        second = { ReviewQueue(state,model,{tab=3},{tab=2}) },
                                    )
                                }
                                item {
                                    AdaptivePair(wide,
                                        first = { LeaderPanel(state, model::openLeader, { leadersPage = true }) },
                                        second = { LadderPanel(state.demo) },
                                    )
                                }
                                item { MonitorPanel(state, model) }
                            }
                            1 -> {
                                item {
                                    AdaptivePair(wide,
                                        first = {
                                    Panel {
                                        SectionTitle("01", "美国与大宗商品", "跨市场观察")
                                        if (state.us.isEmpty())
                                            Empty("美国重要标的尚未加载", "连接后读取后端配置的美股观察池")
                                        else state.us.forEach { QuoteRow(it) { model.openStock(it) } }
                                        EventPanel(MarketScope.US, state, model)
                                        HorizontalDivider(color = Line)
                                        QuoteRow(
                                            Quote(
                                                "gold",
                                                "纽约金主连",
                                                "COMEX · USD/oz",
                                                if (state.demo) 2658.4 else null,
                                                if (state.demo) .82 else null,
                                                if (state.demo) "演示" else "待接入",
                                            )
                                        )
                                        EventPanel(MarketScope.GOLD, state, model)
                                        QuoteRow(
                                            Quote(
                                                "brent",
                                                "布伦特原油",
                                                "BRENT · USD/bbl",
                                                if (state.demo) 74.26 else null,
                                                if (state.demo) -1.24 else null,
                                                if (state.demo) "演示" else "待接入",
                                            )
                                        )
                                        EventPanel(MarketScope.OIL, state, model)
                                        Caption("合约与币种独立展示；主连换月规则待行情源接入后明确。")
                                    }
                                        },
                                        second = {
                                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Panel {
                                        SectionTitle("02", "亚太市场", "区域风向")
                                        QuoteRow(
                                            Quote(
                                                "nikkei",
                                                "日经 225",
                                                "NIKKEI · JPY",
                                                if (state.demo) 38925.63 else null,
                                                if (state.demo) 1.18 else null,
                                                if (state.demo) "演示" else "待接入",
                                            )
                                        )
                                        EventPanel(MarketScope.JP, state, model)
                                        QuoteRow(
                                            Quote(
                                                "kospi",
                                                "韩国综合指数",
                                                "KOSPI · KRW",
                                                if (state.demo) 2593.27 else null,
                                                if (state.demo) -.36 else null,
                                                if (state.demo) "演示" else "待接入",
                                            )
                                        )
                                        EventPanel(MarketScope.KR, state, model)
                                    }
                                    Panel {
                                        SectionTitle("03", "A 股风向", "银行 / 大盘 / 双创")
                                        listOf(
                                                "中证银行" to "银行",
                                                "上证指数" to "大盘",
                                                "深证成指" to "大盘",
                                                "创业板指" to "双创",
                                                "科创 50" to "双创",
                                            )
                                            .forEachIndexed { i, (name, group) ->
                                                QuoteRow(
                                                    Quote(
                                                        "index$i",
                                                        name,
                                                        group,
                                                        if (state.demo)
                                                            listOf(
                                                                1428.3,
                                                                3336.5,
                                                                10529.7,
                                                                2187.2,
                                                                987.6,
                                                            )[i]
                                                        else null,
                                                        if (state.demo)
                                                            listOf(-.42, .68, .93, 1.38, 2.16)[i]
                                                        else null,
                                                        if (state.demo) "演示" else "待接入",
                                                    )
                                                )
                                            }
                                        Caption("指数点位不可用 ETF 价格替代。以上指数接口尚待后端接入。")
                                    }
                                            }
                                        },
                                    )
                                }
                            }
                            2 -> {
                                item { ScanControl(state,model) { filters=true } }
                                item {
                                    SearchField(signalSearch,{signalSearch=it},"在筛选结果中搜索名称或代码","signal-search")
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("符合条件", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${displayedSignals.size} / ${state.signals.size} 个标的",
                                            color = Muted,
                                            fontSize = 13.sp,
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Caption(state.scanNote)
                                    if(historicalScan(state)) Text("历史筛选 · 当前目标交易日 ${scanReferenceDate(state)}，请重新扫描后核验",color=Rise,fontSize=12.sp,modifier=Modifier.testTag("historical-scan"))
                                    if(state.scanPhase==ScanPhase.RUNNING || state.scanPhase==ScanPhase.STOPPED || state.scanPhase==ScanPhase.FAILED) Caption("以下为已处理范围内的部分结果，不能代表全量筛选结果。")
                                    if(state.scanning) LinearProgressIndicator(progress={state.scanProgress?.let { if(it.total>0) it.processed.toFloat()/it.total else 0f } ?: 0f},modifier=Modifier.fillMaxWidth().padding(top=8.dp))
                                }
                                if (displayedSignals.isEmpty()) item {
                                    Panel {
                                        Empty(when {
                                            state.scanPhase==ScanPhase.IDLE -> "尚未开始技术扫描"
                                            state.scanning -> "正在扫描，请稍候"
                                            state.scanPhase==ScanPhase.FAILED -> "扫描未完成"
                                            state.scanPhase==ScanPhase.STOPPED -> "扫描已停止"
                                            signalSearch.isNotBlank() -> "筛选结果中没有匹配标的"
                                            else -> "暂无符合条件的标的"
                                        },if(signalSearch.isNotBlank()) "可清空搜索，查看其他筛选结果。" else "设置技术条件后主动扫描；符合条件的标的可手动加入观察池。")
                                    }
                                }
                                displayedSignals.forEach { signal ->
                                    item(key="signal-${signal.quote.id}") { SignalReviewCard(signal,state,model) { tab=3 } }
                                }
                                item {
                                    Caption(
                                        "分页扫描全部已登记 A 股，不局限观察池。市场覆盖取决于后端目录；缺日、除权分红可能影响结果。手动加入观察池，不自动买卖。"
                                    )
                                }
                            }
                            3 -> watchPoolItems(state,model,poolSearch,{poolSearch=it},{tab=2}) { aiSettings=true }
                        }
                        item {
                            Text(
                                "观市  /  保持观察，独立判断",
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                fontSize = 10.sp,
                                letterSpacing = 1.sp,
                                color = Muted,
                            )
                        }
                    }
                }
            }
        }
        if (settings)
            ConnectionDialog(
                initialUrl=state.serviceUrl,
                sessionActive=state.demo || state.hasConnection,
                onDisconnect={model.disconnect();settings=false},
                onDismiss = { settings = false },
                onConnect = { url, token ->
                    model.connect(url, token)
                    settings = false
                },
                onDemo = {
                    model.demo()
                    settings = false
                },
                onAi = { settings=false;aiSettings=true },
            )
        if(aiSettings) AiConfigurationDialog(state,model) { aiSettings=false }
        if (filters)
            FilterDialog(
                state.rules,
                onDismiss = { filters = false },
                onApply = {
                    model.filter(it)
                    model.scan()
                    filters = false
                },
            )

    }
}

private val tabs = listOf("今日主线", "全球行情", "信号雷达", "观察池")

@Composable
private fun WorkspaceToolbar(state: ScreenState, wide: Boolean, onRefresh: () -> Unit, onSettings: () -> Unit) {
    Surface(color = Paper) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (wide) 28.dp else 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (wide) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("市场工作台", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("MARKET OBSERVATORY", fontSize = 9.sp, color = Muted, letterSpacing = 1.5.sp)
                    }
                } else {
                    Brand()
                    Spacer(Modifier.weight(1f))
                }
                if (wide) Text(if (state.demo) "演示数据 · 非实时行情" else state.date, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 16.dp))
                IconButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Refresh, "刷新", Modifier.size(21.dp))
                }
                IconButton(onClick = onSettings, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Tune, "连接与设置", Modifier.size(21.dp))
                }
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable
private fun ConnectionBanner(state: ScreenState, wide: Boolean, onConnect: () -> Unit, onDemo: () -> Unit) {
    val disconnected = !state.hasConnection && !state.demo
    if(!disconnected) {
        Row(Modifier.fillMaxWidth().background(if(state.demo) Color(0xFFF8EFE0) else Color(0xFFE6EEE8),RoundedCornerShape(8.dp)).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(state.demo) "演示模式 · 模拟行情" else "收盘快照 · ${state.date}",Modifier.weight(1f),fontSize=11.sp,color=Muted)
            Text(if(state.demo) "不代表实盘" else if(state.loading) "更新中" else "非实时行情",fontSize=11.sp,color=Muted)
        }
        return
    }
    Surface(color = if (state.demo) Color(0xFFF8EFE0) else Color(0xFFE6EEE8), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (state.demo) Icons.Outlined.Science else if (disconnected) Icons.Outlined.Link else Icons.Outlined.CheckCircle,
                    null, Modifier.size(20.dp), tint = if (state.demo) Color(0xFF8A632B) else Ink)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (state.demo) "演示模式" else if (disconnected) "开启你的市场观察" else if(state.loading) "正在更新行情" else if(state.sectionErrors.isNotEmpty()) "数据服务 · 部分内容待重试" else "数据服务已配置", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (disconnected) "连接数据服务，或先探索演示工作台" else state.note, fontSize = 11.sp, color = Muted, lineHeight = 16.sp)
                }
                if (!disconnected && wide) Text(state.date, fontSize = 11.sp, color = Muted)
            }
            if (disconnected) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onConnect, shape = RoundedCornerShape(9.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("连接数据", fontSize = 12.sp)
                }
                TextButton(onClick = onDemo) { Text("体验演示", fontSize = 12.sp); Spacer(Modifier.width(5.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable
private fun Overview(state: ScreenState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val leading = state.boards.filter { it.change != null }.maxByOrNull { it.change!! }
        listOf(
            Triple("观察板块", state.boards.size.toString(), if (state.demo) "演示范围" else "已采集范围"),
            Triple("领先板块", leading?.name ?: "—", pct(leading?.change)),
            Triple("龙虎榜披露", state.leaders.size.toString(), "已加载条目"),
        ).forEachIndexed { i, (label, value, note) ->
            Surface(Modifier.weight(1f), color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Line)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(label, color = Muted, fontSize = 11.sp)
                    Text(value, fontSize = if (i == 1) 15.sp else 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(note, fontSize = 10.sp, color = if (i == 1) color(leading?.change) else Muted)
                }
            }
        }
    }
}

/** A lens around a rising market line, shared with the launcher artwork. */
@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = size.minDimension / 64f
        drawRoundRect(Ink, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16 * unit))
        drawArc(Color(0xFFE7EDC8), 40f, 280f, false, Offset(13 * unit, 13 * unit), androidx.compose.ui.geometry.Size(38 * unit, 38 * unit), style = Stroke(3 * unit, cap = StrokeCap.Round))
        val path = Path().apply { moveTo(20 * unit, 37 * unit); lineTo(28 * unit, 29 * unit); lineTo(34 * unit, 34 * unit); lineTo(44 * unit, 23 * unit) }
        drawPath(path, Color.White, style = Stroke(3 * unit, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(Color(0xFFBBD78C), 3 * unit, Offset(46 * unit, 47 * unit))
    }
}

private fun tabIcon(i: Int) =
    when (i) {
        0 -> Icons.Outlined.CandlestickChart
        1 -> Icons.Outlined.Language
        3 -> Icons.Outlined.Bookmarks
        else -> Icons.Outlined.QueryStats
    }

@Composable
private fun Brand() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BrandMark(Modifier.size(36.dp))
        Text("观市", fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
internal fun Panel(spacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        color = Color.White,
        shape = shape,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
private fun SectionTitle(number: String, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Ink.copy(alpha = .06f), shape = RoundedCornerShape(8.dp)) {
            Icon(when (title) {
                "板块轮动" -> Icons.Outlined.BarChart
                "重点板块观察" -> Icons.Outlined.TrackChanges
                "龙虎榜" -> Icons.AutoMirrored.Outlined.ReceiptLong
                "连板梯队" -> Icons.Outlined.StackedLineChart
                else -> Icons.Outlined.Language
            }, null, Modifier.padding(7.dp).size(18.dp), tint = Ink)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        Text(subtitle, color = Muted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
internal fun Caption(text: String) {
    Text(text, fontSize = 11.sp, color = Muted, lineHeight = 18.sp)
}

@Composable
internal fun Empty(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().background(Paper, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Inbox, null, Modifier.size(24.dp), tint = Muted)
        Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Caption(description)
    }
}

@Composable
internal fun Metric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Caption(label)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AdaptivePair(
    wide: Boolean,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    if (wide)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1.2f)) { first() }
            Box(Modifier.weight(1f)) { second() }
        }
    else
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            first()
            second()
        }
}

@Composable
private fun BoardPanel(state: ScreenState, onSelect: (Board) -> Unit) {
    var descending by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    val ordered=remember(state.boards,descending) { state.boards.sortedWith(compareBy<Board> { it.change==null }.thenBy { if(descending) -(it.change ?: 0.0) else it.change ?: 0.0 }) }
    Panel {
        SectionTitle("01", "板块轮动", "收盘涨跌幅")
        if(state.boards.isNotEmpty()) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(descending,{descending=true},label={Text("涨幅优先")},modifier=Modifier.testTag("boards-gainers"))
            FilterChip(!descending,{descending=false},label={Text("跌幅优先")},modifier=Modifier.testTag("boards-decliners"))
        }
        if (state.boards.isEmpty()) Empty("等待今日板块数据", "仅展示目标交易日已采集的行业，不自动回退旧行情。")
        else
            ordered.take(if(expanded) ordered.size else 6).forEachIndexed { index, b ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (state.selected?.id == b.id) Ink.copy(alpha = .05f) else Color.Transparent, RoundedCornerShape(8.dp))
                        .testTag("open-board-${b.id}").clickable { onSelect(b) }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text((index + 1).toString().padStart(2, '0'), color = Muted, fontSize = 11.sp)
                    Text(b.name, Modifier.width(72.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(
                        Modifier.weight(1f)
                            .height(7.dp)
                            .background(Paper, RoundedCornerShape(4.dp))
                    ) {
                        Box(
                            Modifier.fillMaxWidth(
                                    ((abs(b.change ?: 0.0) /
                                                max(
                                                    4.0,
                                                    state.boards.maxOf { abs(it.change ?: 0.0) },
                                                ))
                                            .toFloat())
                                        .coerceIn(.01f, 1f)
                                )
                                .fillMaxHeight()
                                .background(
                                    color(b.change).copy(alpha = .65f),
                                    RoundedCornerShape(4.dp),
                                )
                        )
                    }
                    Text(
                        pct(b.change),
                        color = color(b.change),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(64.dp),
                    )
                }
            }
        if(ordered.size>6) TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "收起板块" else "展开其余 ${ordered.size-6} 个板块") }
        Caption("${state.date} · 仅比较已加载 ${state.boards.size} 个板块，不代表全市场涨跌分布")
    }
}

@Composable
private fun LeaderPanel(state: ScreenState, onStock: (Leader) -> Unit, onAll: () -> Unit) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle("02", "龙虎榜", "逐条披露") }
            TextButton(onClick = onAll, modifier = Modifier.testTag("open-leaders")) { Text("查看全部") }
        }
        Caption("披露日期：${state.leaderDate.ifBlank { "尚未取得" }}")
        if (state.leaders.isEmpty()) Empty("暂无已核验披露", "无数据不代表当日没有龙虎榜。")
        else
            state.leaders.take(8).forEach { l ->
                Row(
                    Modifier.fillMaxWidth().clickable { onStock(l) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(l.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Caption("${l.symbol} · ${l.reason}")
                    }
                    Text(
                        money(l.net),
                        color = color(l.net),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                }
            }
        Caption("净买额 / 元转亿元 · 不同上榜原因、单日与多日榜不合并求和")
    }
}

@Composable
private fun LadderPanel(demo: Boolean) {
    Panel {
        SectionTitle("03", "连板梯队", "市场情绪")
        if (demo) {
            listOf("5 板" to "模拟标的 A", "3 板" to "模拟标的 B / C", "2 板" to "模拟标的 D / E / F")
                .forEachIndexed { i, (n, names) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(
                                Rise.copy(alpha = .13f - i * .03f),
                                RoundedCornerShape(10.dp),
                            )
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(n, color = Rise, fontWeight = FontWeight.Bold)
                        Text(names, fontSize = 12.sp, color = Ink)
                    }
                }
            Caption("演示梯队 · 非真实涨停或连板统计")
        } else Empty("连板数据待接入", "后端尚未提供涨停价、特殊交易规则与连续涨停统计。")
    }
}

@Composable
internal fun QuoteRow(q: Quote, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(q.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Caption(q.symbol + if (q.date.isNotBlank()) " · ${q.date}" else "")
        }
        if (q.trend.isNotEmpty())
            Sparkline(q.trend, Modifier.width(62.dp).height(28.dp), color(q.change))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(decimal(q.price), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Surface(color = color(q.change).copy(alpha = .08f), shape = RoundedCornerShape(5.dp)) {
                Text(pct(q.change), Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 12.sp, color = color(q.change), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun Sparkline(values: List<Double>, modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        if (values.size > 1) {
            val lo = values.min()
            val span = (values.max() - lo).coerceAtLeast(.01)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = i.toFloat() / (values.size - 1) * size.width
                val y = size.height - 4 - ((v - lo) / span * (size.height - 8)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLine(Line, Offset(0f, size.height - 1), Offset(size.width, size.height - 1))
            drawPath(path, tint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun ConnectionDialog(
    initialUrl: String,
    sessionActive: Boolean,
    onDisconnect: ()->Unit,
    onDismiss: () -> Unit,
    onConnect: (String, String) -> Unit,
    onDemo: () -> Unit,
    onAi: () -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接数据服务") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Caption("使用已部署的 HTTPS API 地址。令牌仅保存在本次应用内存中，关闭应用后清除。")
                OutlinedTextField(
                    url,
                    { url = it },
                    label = { Text("HTTPS 服务地址") },
                    placeholder = { Text("https://api.example.com") },
                    singleLine = true,
                )
                OutlinedTextField(
                    token,
                    { token = it },
                    label = { Text("访问令牌") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Caption("公网服务未启用时，可先体验演示。演示不会请求后端。")
                TextButton(onClick=onAi) { Text("国内大模型 API · 预留入口") }
                if(sessionActive) TextButton(onClick=onDisconnect) { Text("断开连接 / 退出演示",color=Rise) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConnect(url, token) },
                enabled = url.isNotBlank() && token.isNotBlank(),
            ) {
                Text("连接")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDemo) { Text("演示模式") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun FilterDialog(rules: Rules, onDismiss: () -> Unit, onApply: (Rules) -> Unit) {
    var r by remember { mutableStateOf(rules) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("技术筛选条件") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (r.preset != StrategyPreset.CUSTOM) {
                    Text(r.preset.title, fontWeight = FontWeight.SemiBold)
                    StrategyParametersEditor(r.parameters) { r = r.copy(parameters = it) }
                } else {
                Caption("条件同时满足时入选 · 修改后重新扫描计算")
                Text("均线周期")
                Column {
                    DAILY_MA_PERIODS.chunked(2).forEach { periods ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            periods.forEach { n ->
                                FilterChip(
                                    r.ma == n,
                                    { r = r.copy(ma = n) },
                                    label = { Text("MA$n", maxLines = 1) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("收盘价高于均线", Modifier.weight(1f))
                    Switch(r.aboveMa, { r = r.copy(aboveMa = it) })
                }
                Text("RSI (14) 区间  ${r.rsiMin} – ${r.rsiMax}")
                RangeSlider(
                    value = r.rsiMin.toFloat()..r.rsiMax.toFloat(),
                    onValueChange = {
                        r =
                            r.copy(
                                rsiMin = it.start.roundToInt(),
                                rsiMax = it.endInclusive.roundToInt(),
                            )
                    },
                    valueRange = 0f..100f,
                )
                Text("20 日量比 ≥ ${decimal(r.volume,1)}")
                Slider(
                    r.volume.toFloat(),
                    { r = r.copy(volume = (it * 10).roundToInt() / 10.0) },
                    valueRange = 0f..4f,
                    steps = 39,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("突破此前 20 根日线最高价", Modifier.weight(1f))
                    Switch(r.breakout, { r = r.copy(breakout = it) })
                }
                }
                Caption("筛选设定在当前会话内保留。量比使用此前 20 根日线，不含当天。")
            }
        },
        confirmButton = { TextButton(onClick = { onApply(r) }) { Text("应用并扫描") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
