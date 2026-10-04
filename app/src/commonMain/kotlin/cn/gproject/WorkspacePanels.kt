package cn.gproject

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun LazyListScope.watchPoolItems(state: ScreenState,model: MarketViewModel,query:String,onQuery:(String)->Unit,onScan:()->Unit,onAi: ()->Unit) {
    val allStocks=orderedWatchlist(state.workspace.settings.stocks)
    val stocks=allStocks.filter { matchesSearch(it.name,it.symbol,query) }
    val reviewCount=allStocks.count { stock -> state.workspace.context.associations[stock.id].orEmpty().any { board ->
        flowAttention(state.monitor.flows[board.id],state.monitor.targetDate,state.monitor.settings.days,state.demo).needsReview
    } }
    item {
        Panel(spacing=8.dp) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("我的观察池",Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                Text("${stocks.size} / ${allStocks.size} 个标的",fontSize=12.sp,color=Muted)
                IconButton(onClick=model::refreshPool,enabled=!state.monitor.loading) { Icon(Icons.Outlined.Refresh,"刷新观察池") }
            }
            if(reviewCount>0) Text("全池 $reviewCount 个标的的关联板块出现连续净流出",fontSize=12.sp,color=Rise,fontWeight=FontWeight.Medium)
            else Caption("置顶优先 · 先核对行情，再查看板块资金")
            SearchField(query,onQuery,"搜索观察池名称或代码","pool-search")
            Row(verticalAlignment=Alignment.CenterVertically) {
                Caption("资金周期")
                Spacer(Modifier.width(10.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(5,10,20).forEach { n ->
                    FilterChip(state.monitor.settings.days==n,{model.setFlowDays(n)},label={Text("${n}日")})
                } }
            }
            if(!state.hasConnection && !state.demo) Caption("尚未连接行情服务，请先配置连接")
            else Caption(if(state.demo) "演示快照 · 非真实归属与资金流" else "目标交易日 ${state.monitor.targetDate.ifBlank { "待确认" }} · ${if(state.monitor.settings.enabled) "每 5 分钟检查" else "自动更新已暂停"}")
            if(state.monitor.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(state.workspace.message.isNotBlank()) Caption(state.workspace.message)
            state.workspace.removedStock?.let { removed ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("已移出 ${removed.name}",Modifier.weight(1f),fontSize=12.sp,color=Muted)
                    TextButton(onClick=model::undoRemove,modifier=Modifier.testTag("undo-remove")) { Text("撤销") }
                }
            }
        }
    }
    if(stocks.isEmpty()) item { Panel {
        if(allStocks.isEmpty()) {
            Empty("观察池还是空的","进入信号雷达，扫描后点击符合条件标的的「加入观察池」。")
            TextButton(onClick=onScan) { Text("前往信号雷达") }
        } else Empty("没有匹配的观察标的","尝试其他名称或代码，或清空搜索。")
    } }
    items(stocks,key={ "pool-${it.id}" }) { stock ->
        Panel(spacing=8.dp) {
            val context=state.workspace.context
            val quote=context.quotes[stock.id] ?: stock.quote()
            QuoteRow(quote) { model.openStock(quote) }
            Row(verticalAlignment=Alignment.CenterVertically) {
                if(stock.pinned) {
                    Icon(Icons.Outlined.PushPin,null,tint=Ink,modifier=Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(if(stock.pinned) "已置顶" else "点击行情查看 K 线",Modifier.weight(1f),fontSize=11.sp,color=Muted)
                TextButton(onClick={model.pinStock(stock.id)},modifier=Modifier.testTag("pin-${stock.id}")) { Text(if(stock.pinned) "取消置顶" else "置顶",fontSize=12.sp) }
                TextButton(onClick={model.removeFromPool(stock.id)},modifier=Modifier.testTag("remove-${stock.id}")) { Text("移出",fontSize=12.sp,color=Muted) }
            }
            context.errors[stock.id]?.let { Caption(it) }
            if(!state.demo && quote.date.isNotBlank() && quote.date!=state.monitor.targetDate) Caption("个股历史快照，尚未取得目标交易日行情")
            val boards=context.associations[stock.id].orEmpty()
            if(boards.isEmpty()) Column(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(10.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("所属板块待确认",fontSize=12.sp,fontWeight=FontWeight.Medium)
                Caption("尚未取得同日成分记录，暂不能核对关联板块资金。")
            }
            boards.forEach { board -> PoolBoardContext(board,state,model) }
        }
    }
    item {
        Panel(spacing=6.dp) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("监控与数据",fontSize=14.sp,fontWeight=FontWeight.Medium)
                    Caption("应用内自动更新 · 手机后台暂停")
                }
                Switch(state.monitor.settings.enabled,model::toggleMonitoring,modifier=Modifier.testTag("pool-monitor-toggle"))
            }
            Caption("最近检查 ${localTime(state.monitor.checkedAt)} · 北京时间")
            var details by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={details=!details}) { Text(if(details) "收起数据说明" else "数据与归属说明",fontSize=12.sp) }
                TextButton(onClick=onAi,modifier=Modifier.testTag("ai-settings")) {
                    Icon(Icons.Outlined.AutoAwesome,null,Modifier.size(14.dp));Spacer(Modifier.width(4.dp));Text("AI 接入预留",fontSize=12.sp)
                }
            }
            if(details) {
                Caption(state.workspace.context.note)
                Caption("重新筛选不改变观察池；板块资金不等同于个股资金。缺失或过期数据无法用于判断当前资金状态。")
                if(state.demo) Caption("演示观察池不写入真实观察池。")
            }
        }
    }
}

@Composable
internal fun SearchField(query:String,onQuery:(String)->Unit,hint:String,tag:String) {
    OutlinedTextField(query,onQuery,singleLine=true,modifier=Modifier.fillMaxWidth().testTag(tag),
        label={Text(hint,fontSize=12.sp)},leadingIcon={Icon(Icons.Outlined.Search,null)},
        trailingIcon={if(query.isNotEmpty()) IconButton(onClick={onQuery("")}) { Icon(Icons.Outlined.Close,"清空搜索") }})
}

@Composable
private fun PoolBoardContext(board: Board,state: ScreenState,model: MarketViewModel) {
    val quote=state.workspace.context.boardQuotes[board.id]
    val flow=state.monitor.flows[board.id]
    val days=state.monitor.settings.days
    val summary=flow?.let { summarizeFlows(it,days) }
    val attention=flowAttention(flow,state.monitor.targetDate,days,state.demo)
    val current=flow?.error==null && (state.demo || (state.monitor.targetDate.isNotBlank() && flow?.dates?.lastOrNull()==state.monitor.targetDate))
    val last=flow?.rows?.lastOrNull()
    var expanded by remember(board.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(10.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(board.name,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                Text("同花顺行业 · ${quote?.date ?: "行情待接入"}",fontSize=10.sp,color=Muted)
            }
            Text(pct(quote?.change),fontSize=13.sp,color=color(quote?.change))
            IconButton(onClick={model.openBoard(board)},modifier=Modifier.size(36.dp)) { Icon(Icons.Outlined.ChevronRight,"查看${board.name}详情",Modifier.size(18.dp)) }
        }
        if(attention.needsReview) Row(Modifier.fillMaxWidth().background(Rise.copy(alpha=.07f),RoundedCornerShape(6.dp)).padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info,null,Modifier.size(15.dp),tint=Rise)
            Spacer(Modifier.width(6.dp))
            Text("板块资金关注 · ${attention.label}",fontSize=12.sp,color=Rise,fontWeight=FontWeight.Medium)
        }
        BoxWithConstraints {
            val wide=maxWidth>=620.dp
            val funds: @Composable ()->Unit = {
                Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    if(flow==null || flow.rows.isEmpty()) Caption(flow?.error ?: "板块资金流待接入") else {
                        if(!attention.usable) Text(attention.label,fontSize=12.sp,color=Muted,fontWeight=FontWeight.Medium)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Caption(if(current) "${days}日累计净额" else "历史 ${days}日净额")
                                Text(money(summary?.net),fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=if(current) color(summary?.net) else Muted)
                            }
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Caption("最近一日净额")
                                Text(money(last?.net),fontSize=15.sp,fontWeight=FontWeight.Medium,color=if(current) color(last?.net) else Muted)
                            }
                        }
                        Caption("截至 ${last?.date} · 覆盖 ${summary?.covered}/$days 日 · ${if(flow.scope=="main") "主力口径" else "全口径"} · 人民币")
                    }
                }
            }
            val trend: @Composable ()->Unit = {
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(quote!=null && quote.trend.isNotEmpty()) {
                        Sparkline(quote.trend,Modifier.fillMaxWidth().height(42.dp),color(quote.change))
                        Caption("板块收盘走势 · ${quote.trend.size} 根 · ${quote.source}")
                        if(!state.demo && quote.date!=state.monitor.targetDate) Caption("历史行情，目标交易日待更新")
                    } else Caption(state.workspace.context.errors[board.id] ?: "板块走势尚未取得")
                }
            }
            if(wide) Row(horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { funds() }
                Box(Modifier.weight(1f)) { trend() }
            } else Column(verticalArrangement=Arrangement.spacedBy(10.dp)) { funds();trend() }
        }
        if(flow!=null && flow.rows.isNotEmpty()) {
            TextButton(onClick={expanded=!expanded},modifier=Modifier.heightIn(min=36.dp).testTag("pool-flow-details-${board.id}"),contentPadding=PaddingValues(horizontal=0.dp,vertical=4.dp)) { Text(if(expanded) "收起资金明细" else "资金明细与口径",fontSize=11.sp) }
            if(expanded) {
                flow.error?.let { Caption("$it · 历史内容仅供查阅") }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { Metric("净流入日合计",quantity(summary?.positive,"元")) }
                    Box(Modifier.weight(1f)) { Metric("净流出日合计",quantity(summary?.negative,"元")) }
                }
                Caption("最近 ${last?.date}：流入 ${quantity(last?.inflow,"元")} / 流出 ${quantity(last?.outflow,"元")}")
                Caption("${flow.source} · 人民币 · 更新 ${localTime(flow.updatedAt)}（北京时间）")
                Caption("日合计分别累加正、负净额，不是买卖总额；缺日不计算累计净额。板块资金不等同于个股资金。")
            }
        }
    }
}

@Composable
internal fun AiConfigurationDialog(state: ScreenState,model: MarketViewModel,onDismiss: ()->Unit) {
    var config by remember { mutableStateOf(state.workspace.settings.ai) }
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("国内大模型 API")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Caption("预留 OpenAI 兼容 Chat Completions 接入层。后续可扩展舆情、重要事件、财报分析；当前不调用模型、不发送数据。")
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                items(listOf("自定义国内模型","通义千问","DeepSeek","智谱","豆包")) { provider -> FilterChip(config.provider==provider,{config=config.copy(provider=provider)},label={Text(provider)}) }
            }
            OutlinedTextField(config.baseUrl,{config=config.copy(baseUrl=it)},label={Text("HTTPS API 根地址")},placeholder={Text("https://your-provider.example/v1")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("ai-url"))
            OutlinedTextField(config.model,{config=config.copy(model=it)},label={Text("模型名称 / 部署 ID")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("ai-model"))
            OutlinedTextField(apiKey,{apiKey=it},label={Text("API Key（仅本次会话）")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().testTag("ai-key"))
            Caption("请填写供应商的兼容接口地址。服务名称仅用于标记，不代表所有原生接口都兼容。")
            Caption(if(state.workspace.aiKeyPresent) "本次会话已有密钥；同服务留空可保留，更换服务会清除旧密钥。" else "密钥不写入磁盘，重启后需重新输入；地址与模型配置可本地保存。")
            if(state.demo) Caption("演示模式配置只在本次会话有效。")
            if(state.workspace.aiKeyPresent) TextButton(onClick={model.clearAiKey();apiKey=""}) { Text("清除会话密钥") }
            Text("后续分析模块",fontWeight=FontWeight.SemiBold,fontSize=13.sp)
            Caption("舆情摘要 · 重要事件解读 · 财报分析\n分析按钮将在接入数据与模型适配器后启用。")
            error?.let { Text(it,color=Rise,fontSize=12.sp) }
        }
    },confirmButton={TextButton(onClick={
        error=validateAiConfiguration(config.copy(baseUrl=config.baseUrl.trim(),model=config.model.trim()))
        if(error==null) { if(model.saveAiConfiguration(config,apiKey)) onDismiss() else error="本地保存失败，本次会话仍有效，请重试保存" }
    },modifier=Modifier.testTag("save-ai")) { Text("保存配置") }},dismissButton={TextButton(onClick=onDismiss) { Text("取消") }})
}
