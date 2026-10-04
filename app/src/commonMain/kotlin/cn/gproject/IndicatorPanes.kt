package cn.gproject

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

private val DifColor=Color(0xFFAD761F)
private val DeaColor=Color(0xFF397DC0)

@Composable
internal fun MacdPane(bars:List<Candle>,points:List<MacdPoint?>,start:Int,stop:Int,index:Int,onSelect:(Int)->Unit) {
    val selected=points[index]
    Column(verticalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.testTag("macd-panel")) {
        Text("MACD · 12 / 26 / 9",fontWeight=FontWeight.SemiBold,fontSize=12.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("DIF ${decimal(selected?.dif,3)}",fontSize=11.sp,color=DifColor,modifier=Modifier.testTag("macd-dif"))
            Text("DEA ${decimal(selected?.dea,3)}",fontSize=11.sp,color=DeaColor)
            Text("柱 ${decimal(selected?.histogram,3)}",fontSize=11.sp,color=color(selected?.histogram))
        }
        if(selected==null) Caption("${bars[index].date} · 不足 34 根当前周期行情，MACD 待形成")
        IndicatorPlot(points.map { it?.histogram },listOf(points.map { it?.dif } to DifColor,points.map { it?.dea } to DeaColor),start,stop,index,"macd-chart",false,onSelect)
        Text("柱 = 2 × (DIF − DEA) · 零轴上红、下绿 · 与上方 K 线同步选日",fontSize=10.sp,color=Muted)
    }
}

@Composable
internal fun StockFundsPane(bars:List<Candle>,dailyBars:List<Candle>,period:ChartPeriod,funds:StockFundSeries?,start:Int,stop:Int,index:Int,onSelect:(Int)->Unit) {
    val points=remember(dailyBars,bars,period,funds) { alignedStockFunds(dailyBars,bars,period,funds) }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.testTag("stock-funds-panel")) {
        Text("主力资金 · ${if(period==ChartPeriod.DAY) "日净额" else "本周期净额"}",fontWeight=FontWeight.SemiBold,fontSize=12.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(bars[index].date,fontSize=11.sp,color=Muted)
            Text(money(points[index]),fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=color(points[index]),modifier=Modifier.testTag("selected-fund-net"))
        }
        if(funds?.error!=null) Text(funds.error,fontSize=11.sp,color=Muted)
        if(points.subList(start,stop).any { it!=null }) {
            IndicatorPlot(points,emptyList(),start,stop,index,"stock-funds-chart",true,onSelect)
            if(points[index]==null) Caption("所选日期缺失资金数据；周期汇总需覆盖该周期全部已观测日线。")
            Caption("${funds?.source} · 主力口径 · 人民币元（图轴：亿元） · 不等同于成交额")
            if(period!=ChartPeriod.DAY) Caption("按该周期已观测交易日求和；首尾周／月可能尚未完整。")
        } else {
            Caption(if(funds==null) "个股主力资金待接入，不用成交量或板块资金代替。" else if(funds.rows.isNotEmpty()) "当前区间缺少完整资金记录，可切换历史区间。" else "暂无可用个股主力资金，不以 0 填补缺失记录。")
        }
    }
}

/** Shared horizontal range with the price pane; signed histograms retain a visible zero axis. */
@Composable
private fun IndicatorPlot(histogram:List<Double?>,lines:List<Pair<List<Double?>,Color>>,start:Int,stop:Int,index:Int,tag:String,inYuan:Boolean,onSelect:(Int)->Unit) {
    val visible=histogram.subList(start,stop)
    val all=visible.filterNotNull()+lines.flatMap { it.first.subList(start,stop).filterNotNull() }
    val magnitude=(all.maxOfOrNull { abs(it) } ?: 0.0).coerceAtLeast(if(inYuan) 1.0 else .001)*1.15
    val measurer=rememberTextMeasurer()
    val axisStyle=TextStyle(color=Muted,fontSize=9.sp)
    Canvas(Modifier.fillMaxWidth().height(116.dp).testTag(tag).semantics { contentDescription="${if(inYuan) "主力资金" else "MACD"}副图，点选同步 K 线" }
        .pointerInput(start,stop) { val axis=58.dp.toPx();detectTapGestures { p -> if(p.x<size.width-axis) onSelect(start+(p.x/(size.width-axis)*(stop-start)).toInt().coerceIn(0,stop-start-1)) } }
        .pointerInput(start,stop) { val axis=58.dp.toPx();detectHorizontalDragGestures { change,_ -> change.consume();onSelect(start+(change.position.x/(size.width-axis)*(stop-start)).toInt().coerceIn(0,stop-start-1)) } }) {
        val right=size.width-58.dp.toPx();val top=8.dp.toPx();val bottom=size.height-8.dp.toPx()
        val step=right/(stop-start)
        fun y(value:Double)=top+((magnitude-value)/(2*magnitude)*(bottom-top)).toFloat()
        fun label(value:Double) { drawText(measurer,decimal(if(inYuan) value/100000000 else value,if(inYuan) 2 else 3),Offset(right+5.dp.toPx(),y(value)-5.dp.toPx()),axisStyle) }
        listOf(magnitude,0.0,-magnitude).forEach { value -> drawLine(if(value==0.0) Muted.copy(alpha=.5f) else Line,Offset(0f,y(value)),Offset(right,y(value)));label(value) }
        clipRect(0f,top,right,bottom) {
            visible.forEachIndexed { i,value ->
                val x=(i+.5f)*step
                if(value==null) drawCircle(Muted.copy(alpha=.5f),1.dp.toPx(),Offset(x,bottom-2.dp.toPx()))
                else if(value!=0.0) drawRect(color(value).copy(alpha=.7f),Offset(x-step*.31f,min(y(0.0),y(value))),Size(step*.62f,max(1.dp.toPx(),abs(y(value)-y(0.0)))))
            }
            lines.forEach { (values,tint) ->
                var began=false;val path=Path()
                for(i in start until stop) { val value=values[i];if(value==null) { began=false;continue };val x=(i-start+.5f)*step
                    if(!began) path.moveTo(x,y(value)) else path.lineTo(x,y(value));began=true
                }
                drawPath(path,tint,style=Stroke(1.3.dp.toPx()))
            }
        }
        val x=(index-start+.5f)*step
        drawLine(Ink.copy(alpha=.65f),Offset(x,top),Offset(x,bottom),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),4.dp.toPx())))
    }
}
