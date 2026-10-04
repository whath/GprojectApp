package cn.gproject

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ScanControl(state:ScreenState,model:MarketViewModel,onFilters:()->Unit) {
    Panel(spacing=8.dp) {
        StrategyPicker(state,model)
        if(state.rules.preset==StrategyPreset.CUSTOM) Caption("MA${state.rules.ma}  ·  RSI ${state.rules.rsiMin}–${state.rules.rsiMax}  ·  量比 ≥ ${decimal(state.rules.volume,1)}")
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=if(state.scanning) model::cancelScan else model::scan,modifier=Modifier.weight(1f).testTag("scan-action")) {
                Text(if(state.scanning) "停止扫描" else if(state.scanPhase==ScanPhase.IDLE) "开始全量扫描" else "重新扫描")
            }
            OutlinedButton(onClick=onFilters) { Text("筛选条件") }
        }
        Caption("已登记 A 股 · 日线收盘筛选 · 观察池需手动加入")
    }
}

@Composable
internal fun SignalReviewCard(signal:Signal,state:ScreenState,model:MarketViewModel,onPool:()->Unit) {
    val inPool=poolContains(state.workspace.settings.stocks,signal.quote)
    Panel(spacing=10.dp) {
        QuoteRow(signal.quote) { model.openStock(signal.quote) }
        if(historicalScan(state)) Text("历史候选 · 信号有效性待重新核验",fontSize=12.sp,color=Rise)
        val visible=orderedReviewSetups(signal.setups).filter { state.rules.preset==StrategyPreset.CUSTOM || it.preset==state.rules.preset || it.preset==StrategyPreset.TOP_RISK || it.preset==StrategyPreset.DOWNTREND }
        visible.forEach { SetupExplanation(it) }
        if(visible.isEmpty()) Caption("满足自定义指标条件，尚无已识别的形态组合；需结合 K 线核验。")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Metric("MA${state.rules.ma}",decimal(signal.ma)) }
            Box(Modifier.weight(1f)) { Metric("RSI · 14",decimal(signal.rsi,1)) }
            Box(Modifier.weight(1f)) { Metric("日量 / 前20日均量",decimal(signal.volume,2)) }
        }
        Caption("${signal.quote.date} · ${signal.quote.source} · 不复权日线 · ${if(signal.breakout) "高于" else "未高于"}前20根最高价")
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={model.openStock(signal.quote)},modifier=Modifier.weight(1f)) { Text("核验 K 线") }
            Button(onClick={model.addToPool(signal.quote)},enabled=!inPool,modifier=Modifier.weight(1f).testTag("add-pool-${signal.quote.id}")) { Text(if(inPool) "已加入观察池" else "加入观察池") }
        }
        if(inPool) TextButton(onClick=onPool) { Text("查看观察池") }
    }
}

@Composable
internal fun ReviewQueue(state:ScreenState,model:MarketViewModel,onPool:()->Unit,onScan:()->Unit) {
    Panel(spacing=10.dp) {
        Text("我的复核清单",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
        Caption("先检查已有观察，再寻找新的技术候选")
        val stocks=orderedWatchlist(state.workspace.settings.stocks)
        if(stocks.isEmpty()) {
            Text("尚未建立观察清单",fontSize=14.sp,fontWeight=FontWeight.Medium)
            Caption("选择技术组合，核验形态与失效条件后手动加入。")
            Button(onClick=onScan) { Text("开始技术筛选") }
        } else {
            stocks.take(3).forEach { stock ->
                QuoteRow(state.workspace.context.quotes[stock.id] ?: stock.quote()) { model.openStock(state.workspace.context.quotes[stock.id] ?: stock.quote()) }
            }
            TextButton(onClick=onPool) { Text("管理 ${stocks.size} 个观察标的") }
        }
        val boards=reviewBoards(state)
        if(boards.isNotEmpty()) {
            HorizontalDivider(color=Line)
            Text("跟踪板块 · ${state.monitor.settings.days} 日资金",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            boards.take(4).forEach { board ->
                val attention=flowAttention(state.monitor.flows[board.id],state.monitor.targetDate,state.monitor.settings.days,state.demo)
                Row(Modifier.fillMaxWidth().clickable { model.openBoard(board) }.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(board.name,Modifier.weight(1f),fontSize=13.sp)
                    Text(attention.label,fontSize=11.sp,color=if(attention.needsReview) Rise else Muted)
                }
            }
            Caption(if(state.demo) "模拟资金 · 非真实交易提示" else "待接入、历史或覆盖不足的资金不作当前判断")
        }
    }
}
