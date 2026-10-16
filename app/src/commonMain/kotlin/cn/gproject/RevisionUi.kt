package cn.gproject

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.*
import kotlin.math.*

private val DailyShape = RoundedCornerShape(18.dp)
private val Warm = Color(0xFF9B6B26)
private val DailyTabs = listOf("市场", "板块", "宏观", "资讯", "自选")
private val DailyIcons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.AccountTree, Icons.Outlined.Public,
    Icons.Outlined.Article, Icons.Outlined.BookmarkBorder)

@Composable
fun App(model: RevisedViewModel) {
    val state by model.ui.collectAsState()
    var tab by rememberSaveable { mutableStateOf("市场") }
    var connection by remember { mutableStateOf(false) }
    var selectedLeader by remember { mutableStateOf<Leader?>(null) }
    val visible = buildList {
        add("市场")
        if (state.rankings.any { it.key in listOf("industry","concept") && it.value.items.isNotEmpty() } || state.funds.values.any { it.isNotEmpty() }) add("板块")
        if (state.macro.isNotEmpty()) add("宏观")
        if (state.policy.isNotEmpty() || state.calendar.isNotEmpty()) add("资讯")
        add("自选")
    }
    val active = tab.takeIf { it in visible } ?: "市场"
    DetailBackHandler(state.detail != null) { model.back() }
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Fall, background = Paper,
        surface = Color.White, onSurface = Ink, onBackground = Ink, outline = Line,
        primaryContainer = Color(0xFFE5F0EB),secondaryContainer=Color(0xFFE0ECE5),onSecondaryContainer=Ink,surfaceVariant = Color(0xFFEDF2EF))) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Paper).safeDrawingPadding()) {
            val wide = maxWidth >= 900.dp
            Row(Modifier.fillMaxSize()) {
                if (wide && state.detail == null) Column(Modifier.width(196.dp).fillMaxHeight().background(Color(0xFFEDF2EF)).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("观市", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("收盘之后 · 看清市场", fontSize = 11.sp, color = Muted)
                    Spacer(Modifier.height(25.dp))
                    visible.forEach { name ->
                        val index = DailyTabs.indexOf(name)
                        Surface(onClick = { tab = name }, color = if (active == name) Ink else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(DailyIcons[index], null, tint = if (active == name) Color.White else Muted, modifier = Modifier.size(21.dp))
                                Text(name, color = if (active == name) Color.White else Ink, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("收盘行情与资讯\n数据与来源，清楚可见", color = Muted, fontSize = 11.sp, lineHeight = 20.sp)
                }
                Scaffold(Modifier.weight(1f), containerColor = Paper,
                    topBar = {
                        Row(Modifier.fillMaxWidth().padding(horizontal = if (wide) 28.dp else 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.detail != null) IconButton(onClick = model::back, modifier = Modifier.testTag("daily-back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                            Column(Modifier.weight(1f)) {
                                Text(if (state.detail != null) state.detail!!.asset.name else if (wide) "收盘数据工作台" else "观市", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text(if (state.detail != null) state.detail!!.asset.symbol else if(state.overview.stockHistoryWeeks==52) "A 股近 52 周日线" else "近 30 个交易日", fontSize = 11.sp, color = Muted)
                            }
                            if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            IconButton(onClick = model::refresh, enabled = state.connected && !state.loading, modifier = Modifier.testTag("daily-refresh")) { Icon(Icons.Outlined.Refresh, "刷新数据") }
                            IconButton(onClick = { connection = true }, modifier = Modifier.testTag("daily-settings")) { Icon(Icons.Outlined.Settings, "连接设置") }
                        }
                    },
                    bottomBar = {
                        if (!wide && state.detail == null) NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                            visible.forEach { name -> NavigationBarItem(selected = active == name, onClick = { tab = name },
                                icon = { Icon(DailyIcons[DailyTabs.indexOf(name)], null, Modifier.size(21.dp)) }, label = { Text(name, fontSize = 11.sp) }) }
                        }
                    }) { padding ->
                    val detail = state.detail
                    if (detail != null) DailyDetailPage(detail, state, wide, model, Modifier.padding(padding))
                    else LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("daily-scroll"),
                        contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 16.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            if (state.preview) DailyNotice("界面测试样本 · 非实时行情")
                            if (!state.connected && !state.preview) WelcomeCard { connection = true }
                            else {
                                Text(when(active) { "市场" -> "收盘复盘"; "板块" -> "资金与主线"; "宏观" -> "宏观观察"; "资讯" -> "日历与政策"; else -> "我的自选" }, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text(when(active) {
                                    "市场" -> "A股 ${state.overview.cn}  ·  美股 ${state.overview.us}"
                                    "板块" -> "东方财富分类  ·  ${state.overview.boards}"
                                    "宏观" -> "各来源独立公布日期，价格与收益率分别呈现"
                                    "资讯" -> "未来 7 天经济事件与近期官方政策"
                                    else -> "只关注自己需要的标的"
                                }, color = Muted, fontSize = 12.sp)
                            }
                        }
                        if (state.message.isNotBlank()) item { DailyNotice(state.message) }
                        if (state.connected && !state.preview && active=="市场" && state.overview.cn.isNotBlank() && state.overview.targets["CN"]!=state.overview.cn) item {
                            DailyNotice("A股快照截至 ${state.overview.cn}，尚未更新至最近交易日 ${state.overview.targets["CN"]}。")
                        }
                        if (state.errors.isNotEmpty()) item { DailyNotice(state.errors.keys.joinToString("、") + "读取失败，已保留可用快照。", onRetry = model::refresh) }
                        if (state.connected || state.preview) when (active) {
                            "市场" -> marketContent(state, wide, model) { selectedLeader = it }
                            "板块" -> item { BoardBrowser(state, model) }
                            "宏观" -> macroContent(state, wide)
                            "资讯" -> newsContent(state)
                            "自选" -> item { WatchBrowser(state, model) }
                        }
                    }
                }
            }
        }
        if (connection) DailyConnection(state, model) { connection = false }
        selectedLeader?.let { leader -> SeatDialog(leader, model) { selectedLeader = null } }
    }
}

@Composable
private fun WelcomeCard(onConnect: () -> Unit) = DailyCard {
    Text("让数据回到复盘本身", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Text("收盘行情、5 日涨幅榜、板块资金，以及宏观和政策资讯。", color = Muted, fontSize = 14.sp, lineHeight = 23.sp)
    Button(onClick = onConnect, modifier = Modifier.testTag("daily-connect")) { Text("连接我的数据服务") }
    Text("访问令牌仅保存在本次会话中。", color = Muted, fontSize = 11.sp)
}

@Composable
private fun DailyCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = DailyShape, color = Color.White, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun DailyNotice(text: String, onRetry: (() -> Unit)? = null) {
    Surface(Modifier.fillMaxWidth(), color = Color(0xFFF8F1E5), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = Warm, fontSize = 12.sp, lineHeight = 19.sp)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

private fun LazyListScope.marketContent(state: DailyUiState, wide: Boolean, model: RevisedViewModel, onLeader: (Leader) -> Unit) {
    if (state.indices.isNotEmpty()) item { DailyCard {
        SectionHeading("市场指数", "点位 · 各市场独立日期")
        state.indices.chunked(if (wide) 4 else 2).forEach { group -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            group.forEach { quote -> Column(Modifier.weight(1f).padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(quote.name, fontSize = 12.sp, color = Muted); Text(decimal(quote.price), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(pct(quote.change), color = color(quote.change), fontSize = 13.sp); Text(quote.date, fontSize = 10.sp, color = Muted)
            } }
            repeat((if (wide) 4 else 2) - group.size) { Spacer(Modifier.weight(1f)) }
        } }
    } }
    if (state.rankings["stock"]?.items?.isNotEmpty() == true || state.rankings["etf"]?.items?.isNotEmpty() == true) item {
        RankingBrowser(state, listOf("stock","etf"), model)
    }
    if (state.usSectors.isNotEmpty()) item { DailyCard {
        SectionHeading("美股行业", "11 类行业 ETF 代理 · 单日价格变化")
        state.usSectors.forEach { AssetRow(it) { model.open(it) } }
    } }
    if (state.usAi.isNotEmpty()) item { DailyCard {
        SectionHeading("AI 核心观察", "配置池 · 单日价格变化")
        state.usAi.forEach { AssetRow(it) { model.open(it) } }
    } }
    if (state.leaders.isNotEmpty()) item { DailyCard {
        var count by rememberSaveable { mutableStateOf(30) }
        SectionHeading("龙虎榜披露", state.overview.lhb)
        state.leaders.take(count).forEach { row ->
            Surface(onClick = { onLeader(row) }, color = Color.Transparent) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row { Text(row.name, Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp); Text(money(row.net), color = color(row.net), fontSize = 13.sp) }
                    Text("${row.symbol} · ${row.reason}", color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if(state.leaders.size>count) TextButton(onClick={count+=30}) { Text("查看更多披露（$count / ${state.leaders.size}）") }
        Text("按披露原因分别展示，金额不可直接跨榜相加。", fontSize = 10.sp, color = Muted)
    } }
}

@Composable
private fun SectionHeading(title: String, caption: String = "") {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    if (caption.isNotBlank()) Text(caption, color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
}

@Composable
private fun RankingBrowser(state: DailyUiState, kinds: List<String>, model: RevisedViewModel) {
    var selected by rememberSaveable(kinds.joinToString()) { mutableStateOf(kinds.first()) }
    val kind = selected.takeIf { state.rankings[it]?.items?.isNotEmpty() == true } ?: kinds.firstOrNull { state.rankings[it]?.items?.isNotEmpty() == true } ?: selected
    val ranking = state.rankings[kind]
    var count by rememberSaveable(kind,ranking?.snapshot) { mutableStateOf(30) }
    DailyCard {
        SectionHeading("近 5 日涨幅榜", "6 个对齐收盘价计算 · 仅含已验证标的")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.filter { state.rankings[it]?.items?.isNotEmpty() == true }.forEach { key ->
                FilterChip(selected = kind == key, onClick = { selected = key }, label = { Text(kindName(key)) })
            }
        }
        if (ranking != null) {
            Text("${ranking.eligible} 个可用 / ${ranking.expected} 个目录标的 · ${ranking.date}", color = Muted, fontSize = 11.sp)
            ranking.items.take(count).forEach { AssetRow(it) { model.open(it) } }
            if(ranking.items.size>count) TextButton(onClick={count+=30}) { Text("查看更多（$count / ${ranking.items.size}）") }
            Text(if (kind in listOf("stock","etf")) "前复权价格涨幅；缺日和过期标的不参与排名。" else "板块指数收盘价涨幅。", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AssetRow(asset: DailyAsset, trailing: (@Composable () -> Unit)? = null, onOpen: () -> Unit) {
    Surface(onClick = onOpen, color = Color.Transparent, modifier = Modifier.testTag("daily-asset-${asset.id}")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (asset.rank > 0) Text(asset.rank.toString().padStart(2,'0'), color = Muted, fontSize = 11.sp, modifier = Modifier.width(23.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(asset.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(asset.symbol, color = Muted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                asset.close?.let { Text(decimal(it), fontSize = 14.sp) }
                asset.change?.let { Text(pct(it), fontSize = 13.sp, color = color(it), fontWeight = FontWeight.SemiBold) }
            }
            if (trailing != null) trailing()
        }
    }
}

private fun kindName(kind: String) = when(kind) { "stock" -> "个股"; "etf" -> "ETF"; "industry" -> "行业"; else -> "概念" }

@Composable
private fun BoardBrowser(state: DailyUiState, model: RevisedViewModel) {
    var funds by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf("industry") }
    val actualKind=kind.takeIf { state.funds[it]?.isNotEmpty()==true } ?: state.funds.keys.firstOrNull { state.funds[it]?.isNotEmpty()==true } ?: kind
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!funds, { funds = false }, label = { Text("5 日涨幅") })
            if (state.funds.values.any { it.isNotEmpty() }) FilterChip(funds, { funds = true }, label = { Text("5 日资金") })
        }
        if (!funds) RankingBrowser(state, listOf("industry","concept"), model)
        else DailyCard {
            SectionHeading("板块主力资金", "近 5 个交易日净额合计 · 东方财富口径")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("industry","concept").filter { state.funds[it]?.isNotEmpty() == true }.forEach { key -> FilterChip(actualKind == key, { kind = key }, label = { Text(kindName(key)) }) }
            }
            state.funds[actualKind].orEmpty().forEach { row ->
                Surface(onClick = { model.open(DailyAsset(row.id,row.name,row.id.substringAfterLast('.'),date=row.date)) }, color = Color.Transparent) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Text(row.name, Modifier.weight(1f), fontSize = 14.sp); Text(money(row.net), color = color(row.net), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                }
            }
            Text("主力净额为大单与超大单净额之和，不代表市场新增资金。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
        }
    }
}

private fun LazyListScope.macroContent(state: DailyUiState, wide: Boolean) {
    state.macro.chunked(if (wide) 2 else 1).forEach { group -> item {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            group.forEach { quote -> DailyCard(Modifier.weight(1f)) {
                SectionHeading(quote.name, when(quote.id) { "gold" -> "美元 / 盎司 · 新浪 XAU 参考报价"; "brent" -> "美元 / 桶 · EIA 现货 FOB"; else -> "美国财政部 · 日度平价收益率" })
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(decimal(quote.value) + if (quote.unit == "percent") "%" else "", Modifier.weight(1f), fontSize = 31.sp, fontWeight = FontWeight.Bold)
                    quote.change?.let { Text(if (quote.changeUnit == "basis_points") (if(it>0) "+" else "")+decimal(it,1)+" bp" else pct(it), color = color(it), fontSize = 14.sp) }
                }
                DailyLine(quote.trend, Modifier.fillMaxWidth().height(88.dp), Fall)
                Text("数据日期 ${quote.date} · 近 ${quote.trend.size} 次公布值", color = Muted, fontSize = 11.sp)
                if (quote.stale) Text("更新暂时失败，当前为保留快照", color = Warm, fontSize = 11.sp)
                if (quote.id == "brent") Text("现货公布存在延迟，以标注日期为准。", color = Muted, fontSize = 10.sp)
                if (quote.id == "gold") Text("CFD 参考行情，非 LBMA 定盘价。", color = Muted, fontSize = 10.sp)
            } }
            if (wide && group.size == 1) Spacer(Modifier.weight(1f))
        }
    } }
}

@Composable
private fun DailyLine(values: List<Double>, modifier: Modifier = Modifier, tint: Color = Fall) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val low = values.minOrNull()!!; val span = max(values.maxOrNull()!! - low, .000001)
        val path = Path()
        values.forEachIndexed { i,v ->
            val x = i * size.width / (values.size-1); val y = size.height * (.9f - ((v-low)/span*.8).toFloat())
            if (i == 0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        drawPath(path,tint,style=Stroke(2.dp.toPx()))
    }
}

private fun LazyListScope.newsContent(state: DailyUiState) {
    item { NewsBrowser(state) }
}

@Composable
private fun NewsBrowser(state: DailyUiState) {
    var policy by rememberSaveable { mutableStateOf(state.calendar.isEmpty()) }
    var country by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf(30) }
    val uri = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.calendar.isNotEmpty()) FilterChip(!policy, { policy = false;count = 30 }, label = { Text("经济日历") })
            if (state.policy.isNotEmpty()) FilterChip(policy, { policy = true;count = 30 }, label = { Text("政策新闻") })
        }
        if (linkError) DailyNotice("暂时无法打开浏览器，请稍后重试。")
        if (policy) {
            state.policy.take(count).forEach { item -> DailyCard {
                Row { Text(item.publisher, Modifier.weight(1f), fontSize = 11.sp, color = Fall); Text(item.date, fontSize = 11.sp, color = Muted) }
                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when(item.category) { "policy_document" -> "政策文件"; "interpretation" -> "政策解读"; else -> "官方发布" }, Modifier.weight(1f), color = Muted, fontSize = 11.sp)
                    TextButton(onClick = { try { uri.openUri(item.url) } catch (_: Exception) { linkError = true } }) { Text("查看原文 ↗", fontSize = 12.sp) }
                }
            } }
            if (state.policy.size > count) OutlinedButton(onClick = { count += 30 }, modifier = Modifier.fillMaxWidth()) { Text("继续查看政策") }
        } else {
            if(state.calendarNote.isNotBlank()) DailyNotice(state.calendarNote)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("" to "全部", "CN" to "中国", "US" to "美国", "JP" to "日本", "KR" to "韩国").forEach { (code,label) -> FilterChip(country == code, { country = code;count = 30 }, label = { Text(label) }) }
            }
            val events = state.calendar.filter { country.isBlank() || it.country == country }
            events.take(count).forEach { event -> DailyCard {
                val local = Instant.parse(event.time).toLocalDateTime(TimeZone.of("Asia/Shanghai"))
                Row { Text("${local.date}  ${local.hour.toString().padStart(2,'0')}:${local.minute.toString().padStart(2,'0')} 北京时间", Modifier.weight(1f), fontSize = 11.sp, color = Muted)
                    if (event.importance >= 3) Text("重要", color = Warm, fontSize = 11.sp) }
                Text(event.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp)
                Text(event.region + if(event.unit.isNotBlank()) " · ${event.unit}" else "", color = Muted, fontSize = 11.sp)
                if (event.actual != null || event.expected != null || event.previous != null) Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    event.actual?.let { MiniValue("公布", it) };event.expected?.let { MiniValue("预期", it) };event.previous?.let { MiniValue("前值", it) }
                }
                event.revised?.let { Text("修正前值 $it", color = Muted, fontSize = 11.sp) }
            } }
            if (events.size > count) OutlinedButton(onClick = { count += 30 }, modifier = Modifier.fillMaxWidth()) { Text("继续查看日历") }
            if (events.isEmpty()) Text("未来 7 天暂无匹配事件", color = Muted, fontSize = 13.sp)
            Text("日历来源：华尔街见闻。时间为计划公布时间。", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun MiniValue(label: String, value: String) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, fontSize = 10.sp, color = Muted); Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun WatchBrowser(state: DailyUiState, model: RevisedViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(query, { query = it;model.search(it) }, modifier = Modifier.fillMaxWidth().testTag("daily-search"),
            label = { Text("搜索已有日线的名称或代码") }, singleLine = true, trailingIcon = { if(state.searching) CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp) else Icon(Icons.Outlined.Search,null) })
        if (query.length >= 2) DailyCard {
            if (!state.searching && state.search.isEmpty()) Text("没有匹配的已采集标的", color = Muted, fontSize = 13.sp)
            state.search.forEach { row -> AssetRow(row, trailing = { IconButton(onClick = { model.toggleWatch(row) }) {
                Icon(if(state.watched.any { it.id == row.id }) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, "切换自选")
            } }) { model.open(row) } }
        }
        DailyCard {
            SectionHeading("自选标的", "保存在当前设备，可随时移除")
            if (state.watched.isEmpty()) Text("在涨幅榜或标的详情中加入自选。", color = Muted, fontSize = 13.sp)
            val quotes = (state.rankings.values.flatMap { it.items } + state.usAi + state.usSectors).associateBy { it.id }
            state.watched.forEach { saved -> AssetRow(quotes[saved.id] ?: saved, trailing = {
                IconButton(onClick = { model.toggleWatch(saved) }, modifier = Modifier.testTag("daily-remove-${saved.id}")) { Icon(Icons.Outlined.Close, "移除自选", Modifier.size(17.dp)) }
            }) { model.open(quotes[saved.id] ?: saved) } }
        }
    }
}

@Composable
private fun DailyDetailPage(detail: DailyDetail, state: DailyUiState, wide: Boolean, model: RevisedViewModel, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("daily-detail-scroll"), contentPadding = PaddingValues(if(wide) 28.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { DailyCard {
            val last = detail.candles.lastOrNull()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(last?.let { decimal(it.close) } ?: detail.asset.close?.let { decimal(it) } ?: detail.asset.name, fontSize = 33.sp, fontWeight = FontWeight.Bold)
                    last?.let { Text("${it.date} · ${if(detail.asset.id.contains(".industry.") || detail.asset.id.contains(".concept.")) "板块指数点位" else detail.currency}", color = Muted, fontSize = 11.sp) }
                    detail.asset.change?.let { Text("${detail.asset.periodSessions} 日涨幅 ${pct(it)}", color = color(it), fontSize = 13.sp) }
                }
                val watched = state.watched.any { it.id == detail.asset.id }
                FilledTonalButton(onClick = { model.toggleWatch(detail.asset) }, modifier = Modifier.testTag("daily-watch")) { Text(if(watched) "已自选" else "+ 自选") }
            }
            if (detail.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            detail.error?.let { DailyNotice(it) { model.open(detail.asset) } }
            if (!detail.loading && detail.error == null && detail.candles.isEmpty()) Text("当前标的尚无已采集日线。", color = Muted, fontSize = 13.sp)
        } }
        if (detail.candles.isNotEmpty()) item { DailyCard {
            var visible by rememberSaveable(detail.asset.id) { mutableStateOf(90) }
            val shown = if(detail.historyWeeks==52) detail.candles.takeLast(visible) else detail.candles
            SectionHeading(if(detail.historyWeeks==52) "近 52 周日线" else "近 30 个交易日日线", "已返回 ${detail.candles.size} 日 · 不复权价格")
            if(detail.historyWeeks==52) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(30 to "近30日",90 to "近90日",400 to "52周").forEach { (count,label) ->
                    FilterChip(visible==count,{visible=count},label={Text(label)})
                }
            }
            CompactCandles(shown)
            Text("${shown.first().date} — ${shown.last().date}", color = Muted, fontSize = 10.sp)
            if (!detail.complete) Text("窗口内存在未验证交易日，请结合已返回日期查看。", color = Warm, fontSize = 11.sp)
        } }
        detail.metrics?.let { metrics -> item { DailyCard {
            SectionHeading("估值与规模", metrics.date)
            Row(horizontalArrangement = Arrangement.spacedBy(25.dp)) {
                metrics.peTtm?.let { MiniValue("PE · TTM", decimal(it)) };metrics.pb?.let { MiniValue("PB", decimal(it)) }
                metrics.turnover?.let { MiniValue("换手率", decimal(it)+"%") }
            }
            metrics.totalCap?.let { Text("总市值 ${decimal(it/100000000)} 亿元", fontSize = 13.sp) }
        } } }
        if (detail.funds.isNotEmpty()) item { DailyCard {
            SectionHeading("主力资金净额", "人民币元 · 大单与超大单口径")
            DailyLine(detail.funds.map { it.second }, Modifier.fillMaxWidth().height(100.dp))
            detail.funds.takeLast(5).reversed().forEach { (date,net) -> Row { Text(date, Modifier.weight(1f), fontSize = 12.sp, color = Muted); Text(money(net), fontSize = 12.sp, color = color(net)) } }
        } }
        if (detail.members.isNotEmpty()) item { DailyCard {
            var count by rememberSaveable(detail.asset.id) { mutableStateOf(30) }
            SectionHeading("当前板块成分", "${detail.members.size} 只 · 观测日期 ${state.overview.members}")
            detail.members.take(count).forEach { row ->
                Surface(onClick = { if(row.available) model.open(DailyAsset(row.id,row.name,row.symbol,date=state.overview.cn)) }, enabled = row.available, color = Color.Transparent) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) { Text(row.name, Modifier.weight(1f), fontSize = 13.sp); Text(row.symbol, color = Muted, fontSize = 12.sp) }
                }
            }
            if(detail.members.size>count) TextButton(onClick={count+=50}) { Text("查看更多成分（$count / ${detail.members.size}）") }
        } }
    }
}

@Composable
private fun CompactCandles(bars: List<Candle>) {
    var selected by remember(bars) { mutableStateOf(bars.lastIndex) }
    val focused = bars[selected.coerceIn(bars.indices)]
    Canvas(Modifier.fillMaxWidth().height(225.dp).testTag("daily-candles").pointerInput(bars) {
        detectTapGestures { point -> selected = (point.x / size.width * bars.size).toInt().coerceIn(bars.indices) }
    }) {
        val (low,high) = chartPriceBounds(bars.flatMap { listOf(it.low,it.high) }); val span = high-low
        fun y(price: Double) = (size.height * (1-(price-low)/span)).toFloat()
        for(i in 1..3) drawLine(Line,Offset(0f,size.height*i/4),Offset(size.width,size.height*i/4),1f)
        val step = size.width / bars.size
        bars.forEachIndexed { i,bar ->
            val x = (i+.5f)*step; val tint = if(bar.close>=bar.open) Rise else Fall
            drawLine(tint,Offset(x,y(bar.high)),Offset(x,y(bar.low)),1.dp.toPx())
            drawRect(tint,Offset(x-step*.28f,min(y(bar.open),y(bar.close))), Size(step*.56f,max(abs(y(bar.close)-y(bar.open)),1.5.dp.toPx())))
        }
        val x=(selected+.5f)*step
        drawLine(Muted.copy(alpha=.4f),Offset(x,0f),Offset(x,size.height),1f)
    }
    Text("${focused.date}  开 ${decimal(focused.open)}  高 ${decimal(focused.high)}\n低 ${decimal(focused.low)}  收 ${decimal(focused.close)}", fontSize = 11.sp, color = Muted, lineHeight = 18.sp)
}

@Composable
private fun DailyConnection(state: DailyUiState, model: RevisedViewModel, onDismiss: () -> Unit) {
    var base by remember { mutableStateOf(state.base) }; var token by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("连接数据服务") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(base,{base=it},label={Text("HTTPS 地址")},singleLine=true,modifier=Modifier.testTag("daily-base"))
            OutlinedTextField(token,{token=it},label={Text("访问令牌")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.testTag("daily-token"))
            Text("令牌仅用于本次连接，不随安装包保存。",fontSize=11.sp,color=Muted)
        } }, confirmButton = { TextButton(onClick = { model.connect(base,token);if(token.isNotBlank() && base.startsWith("https://")) onDismiss() }) { Text("连接") } },
        dismissButton = { Row { if(state.connected) TextButton(onClick={model.disconnect();onDismiss()}) { Text("断开") }; TextButton(onClick=onDismiss) { Text("取消") } } })
}

@Composable
private fun SeatDialog(leader: Leader, model: RevisedViewModel, onDismiss: () -> Unit) {
    var feed by remember(leader) { mutableStateOf<SeatFeed?>(null) }
    LaunchedEffect(leader) { feed = model.seats(leader) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("${leader.name} · 买卖席位")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(leader.date, color=Muted,fontSize=11.sp)
            if(feed==null) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
            feed?.error?.let { Text(it,color=Warm,fontSize=12.sp) }
            feed?.items.orEmpty().forEach { row -> Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("${row.direction} · ${row.seat}",fontSize=13.sp); Text(money(row.net),color=color(row.net),fontSize=12.sp)
                Text(row.reason,color=Muted,fontSize=10.sp)
            } }
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("关闭")}})
}
