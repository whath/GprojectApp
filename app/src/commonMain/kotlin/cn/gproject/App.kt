package cn.gproject

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

private val Ink = Color(0xFF263C35)
private val Muted = Color(0xFF78817A)
private val Paper = Color(0xFFF5F5F0)
private val Rise = Color(0xFFAC615C)
private val Fall = Color(0xFF608573)
private val Line = Color(0xFFE5E8DF)
private val shape = RoundedCornerShape(22.dp)

private fun color(value: Double?) =
    if (value == null || value == 0.0) Muted else if (value > 0) Rise else Fall

internal fun decimal(n: Double?, digits: Int = 2): String {
    if (n == null || !n.isFinite()) return "—"
    val scale = 10.0.pow(digits)
    val rounded = round(abs(n) * scale).toLong()
    return (if (n < 0) "−" else "") +
        (rounded / scale.toLong()).toString() +
        if (digits > 0) "." + (rounded % scale.toLong()).toString().padStart(digits, '0') else ""
}

private fun pct(n: Double?) = if (n == null) "—" else (if (n > 0) "+" else "") + decimal(n) + "%"

private fun money(n: Double?) =
    if (n == null) "—" else (if (n > 0) "+" else "") + decimal(n / 100000000) + " 亿"

@Composable
fun App(model: MarketViewModel) {
    val state by model.ui.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Quote?>(null) }
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
            Row(Modifier.fillMaxSize()) {
                if (wide)
                    Column(
                        Modifier.width(208.dp)
                            .fillMaxHeight()
                            .background(Color(0xFFEDF0E8))
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Brand()
                        Spacer(Modifier.height(40.dp))
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
                    bottomBar = {
                        if (!wide)
                            NavigationBar(containerColor = Color.White) {
                                tabs.forEachIndexed { i, label ->
                                    NavigationBarItem(
                                        selected = tab == i,
                                        onClick = { tab = i },
                                        icon = { Icon(tabIcon(i), null) },
                                        label = { Text(label) },
                                    )
                                }
                            }
                    },
                ) { padding ->
                    LazyColumn(
                        Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(if (wide) 36.dp else 20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    if (!wide) Brand()
                                    else
                                        Text(
                                            "GPROJECT  /  MARKET OBSERVATORY",
                                            fontSize = 10.sp,
                                            letterSpacing = 2.sp,
                                            color = Muted,
                                        )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        if (state.demo) "演示工作台" else "收盘观察 · ${state.date}",
                                        fontSize = 12.sp,
                                        color = Muted,
                                    )
                                }
                                IconButton(onClick = model::refresh, enabled = !state.loading) {
                                    Icon(Icons.Outlined.Refresh, "刷新")
                                }
                                IconButton(onClick = { settings = true }) {
                                    Icon(Icons.Outlined.Settings, "连接与设置")
                                }
                            }
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    listOf("跟随资金，看见主线。", "从全球，到你的市场。", "让信号，先一步浮现。")[tab],
                                    fontSize = if (wide) 30.sp else 25.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-1).sp,
                                )
                                Text(
                                    listOf(
                                        "板块轮动 / 龙虎榜 / 重点观察",
                                        "全球市场 / 风险资产 / A 股风向",
                                        "技术筛选 / 趋势跟踪 / 自定义条件",
                                    )[tab],
                                    fontSize = 12.sp,
                                    color = Muted,
                                )
                            }
                        }
                        item {
                            Surface(
                                color = if (state.demo) Color(0xFFF0E7DC) else Color(0xFFEAF0E8),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.size(6.dp)
                                            .background(
                                                if (state.demo) Rise else Fall,
                                                RoundedCornerShape(3.dp),
                                            )
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        state.note,
                                        Modifier.weight(1f),
                                        fontSize = 11.sp,
                                        color = Ink,
                                    )
                                    if (state.date == "待连接")
                                        TextButton(onClick = model::demo) { Text("体验演示") }
                                }
                            }
                        }
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
                        when (tab) {
                            0 -> {
                                item { BoardPanel(state, model::select) }
                                item {
                                    AdaptivePair(
                                        wide,
                                        first = { LeaderPanel(state) },
                                        second = { LadderPanel(state.demo) },
                                    )
                                }
                                item { FocusPanel(state, model::select, { detail = it }) }
                            }
                            1 -> {
                                item {
                                    Panel {
                                        SectionTitle("01", "美国与大宗商品", "跨市场观察")
                                        if (state.us.isEmpty())
                                            Empty("美国重要标的尚未加载", "连接后读取后端配置的美股观察池")
                                        else state.us.forEach { QuoteRow(it) { detail = it } }
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
                                        Caption("合约与币种独立展示；主连换月规则待行情源接入后明确。")
                                    }
                                }
                                item {
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
                                    }
                                }
                                item {
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
                            }
                            2 -> {
                                item {
                                    Surface(color = Ink, shape = shape) {
                                        Column(
                                            Modifier.fillMaxWidth().padding(24.dp),
                                            verticalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                            Text(
                                                "你的策略，你的观察池",
                                                color = Color.White,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Text(
                                                "MA${state.rules.ma}  ·  RSI ${state.rules.rsiMin}–${state.rules.rsiMax}  ·  量比 ≥ ${decimal(state.rules.volume,1)}",
                                                color = Color(0xFFD1DDD0),
                                                fontSize = 13.sp,
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Button(
                                                    onClick = { filters = true },
                                                    colors =
                                                        ButtonDefaults.buttonColors(
                                                            containerColor = Color(0xFFE6EBDD),
                                                            contentColor = Ink,
                                                        ),
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.Tune,
                                                        null,
                                                        Modifier.size(16.dp),
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("筛选条件")
                                                }
                                                OutlinedButton(
                                                    onClick = model::scan,
                                                    enabled = !state.scanning,
                                                    colors =
                                                        ButtonDefaults.outlinedButtonColors(
                                                            contentColor = Color.White
                                                        ),
                                                ) {
                                                    Text("重新扫描")
                                                }
                                            }
                                        }
                                    }
                                }
                                item {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("符合条件", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${state.signals.size} 个标的",
                                            color = Muted,
                                            fontSize = 13.sp,
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Caption(state.scanNote)
                                }
                                if (state.signals.isEmpty())
                                    item { Panel { Empty("暂无符合条件的标的", "调整筛选阈值，或连接服务后点击重新扫描。") } }
                                state.signals.forEach { signal ->
                                    item {
                                        Panel {
                                            QuoteRow(signal.quote) { detail = signal.quote }
                                            Sparkline(
                                                signal.quote.trend,
                                                Modifier.fillMaxWidth().height(84.dp),
                                                Fall,
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            Row(
                                                Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Metric("MA${state.rules.ma}", decimal(signal.ma))
                                                Metric("RSI · 14", decimal(signal.rsi, 1))
                                                Metric("量比 · 20", decimal(signal.volume, 2))
                                                Metric("20 日突破", if (signal.breakout) "是" else "否")
                                            }
                                        }
                                    }
                                }
                                item {
                                    Caption(
                                        "指标以实际观测日线计算，缺少交易日可能影响窗口；不复权价格会受除权、分红影响。只对当次扫描有效，本版不提供后台实时告警。"
                                    )
                                }
                            }
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
                onDismiss = { settings = false },
                onConnect = { url, token ->
                    model.connect(url, token)
                    settings = false
                },
                onDemo = {
                    model.demo()
                    settings = false
                },
            )
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
        detail?.let { q ->
            AlertDialog(
                onDismissRequest = { detail = null },
                title = { Text(q.name) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${q.symbol} · ${q.date}", color = Muted)
                        Text(decimal(q.price), fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                        Sparkline(q.trend, Modifier.fillMaxWidth().height(140.dp), color(q.change))
                        Text(
                            if (q.trend.isEmpty()) "该入口暂无历史趋势，筛选结果中可查看观测日线。"
                            else "${q.trend.size} 个观测收盘价 · 不复权",
                            fontSize = 12.sp,
                        )
                        Caption("来源：${q.source.ifBlank { "待接入" }}")
                    }
                },
                confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
            )
        }
    }
}

private val tabs = listOf("今日主线", "全球行情", "信号雷达")

private fun tabIcon(i: Int) =
    when (i) {
        0 -> Icons.Outlined.Dashboard
        1 -> Icons.Outlined.Public
        else -> Icons.Outlined.Radar
    }

@Composable
private fun Brand() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(color = Ink, shape = RoundedCornerShape(10.dp)) {
            Icon(
                Icons.Outlined.Insights,
                null,
                Modifier.padding(9.dp).size(23.dp),
                tint = Color(0xFFE7ECDF),
            )
        }
        Text("观市", fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        color = Color.White,
        shape = shape,
        border = BorderStroke(1.dp, Line),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionTitle(number: String, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(number, color = Fall, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            modifier = Modifier.weight(1f),
        )
        Text(subtitle, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 11.sp, color = Muted, lineHeight = 18.sp)
}

@Composable
private fun Empty(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Ink, fontSize = 14.sp)
        Caption(description)
    }
}

@Composable
private fun Metric(label: String, value: String) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1.2f)) { first() }
            Box(Modifier.weight(1f)) { second() }
        }
    else
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            first()
            second()
        }
}

@Composable
private fun BoardPanel(state: ScreenState, onSelect: (Board) -> Unit) {
    Panel {
        SectionTitle("01", "板块轮动", "收盘涨跌幅")
        if (state.boards.isEmpty()) Empty("等待今日板块数据", "仅展示目标交易日已采集的行业，不自动回退旧行情。")
        else
            state.boards.take(10).forEachIndexed { index, b ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(b) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text((index + 1).toString().padStart(2, '0'), color = Muted, fontSize = 11.sp)
                    Text(b.name, Modifier.widthIn(min = 72.dp, max = 110.dp), fontSize = 14.sp)
                    Box(
                        Modifier.weight(1f)
                            .height(22.dp)
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
                                    color(b.change).copy(alpha = .45f),
                                    RoundedCornerShape(4.dp),
                                )
                        )
                    }
                    Text(
                        pct(b.change),
                        color = color(b.change),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(74.dp),
                    )
                }
            }
        Caption("点击板块查看成分表现 · 排名仅反映已采集范围")
    }
}

@Composable
private fun LeaderPanel(state: ScreenState) {
    Panel {
        SectionTitle("02", "龙虎榜", "逐条披露")
        if (state.leaders.isEmpty()) Empty("暂无已核验披露", "无数据不代表当日没有龙虎榜。")
        else
            state.leaders.take(8).forEach { l ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
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
private fun FocusPanel(state: ScreenState, onSelect: (Board) -> Unit, onQuote: (Quote) -> Unit) {
    Panel {
        SectionTitle("04", "重点板块观察", "资金与核心标的")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.boards.take(8).forEach { b ->
                FilterChip(
                    selected = state.selected?.id == b.id,
                    onClick = { onSelect(b) },
                    label = { Text(b.name) },
                )
            }
        }
        if (state.selected == null) Empty("选择一个重点板块", "查看该板块成分与行情，资金流数据在接入后展示。")
        else {
            Text(state.selected.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("主力流入", if (state.demo) "18.62 亿" else "待接入")
                Metric("主力流出", if (state.demo) "12.38 亿" else "待接入")
                Metric("净流入", if (state.demo) "+6.24 亿" else "待接入")
            }
            Caption(if (state.demo) "上述资金为演示值" else "资金流接口尚未提供；成交额不等同于资金流入。 ")
            HorizontalDivider(color = Line)
            state.members.forEach { q -> QuoteRow(q) { onQuote(q) } }
            Caption(state.memberNote)
        }
    }
}

@Composable
private fun QuoteRow(q: Quote, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(q.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Caption(q.symbol + if (q.date.isNotBlank()) " · ${q.date}" else "")
        }
        if (q.trend.isNotEmpty())
            Sparkline(q.trend, Modifier.width(62.dp).height(28.dp), color(q.change))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(decimal(q.price), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(pct(q.change), fontSize = 12.sp, color = color(q.change))
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier, tint: Color) {
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
    onDismiss: () -> Unit,
    onConnect: (String, String) -> Unit,
    onDemo: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接数据服务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                Caption("条件同时满足时入选 · 修改后重新扫描计算")
                Text("均线周期")
                Column {
                    listOf(5, 10, 20, 60).chunked(2).forEach { periods ->
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
                Caption("筛选设定在当前会话内保留。量比使用此前 20 根日线，不含当天。")
            }
        },
        confirmButton = { TextButton(onClick = { onApply(r) }) { Text("应用并扫描") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
