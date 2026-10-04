package cn.gproject

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

/** The same padded bounds drive candles, averages, grid labels and crosshair. */
internal fun chartPriceBounds(values: List<Double>): Pair<Double, Double> {
    val low = values.minOrNull() ?: 0.0
    val high = values.maxOrNull() ?: 1.0
    val pad = max((high-low)*.08, max(abs(high)*.002,.01))
    return low-pad to high+pad
}

@Composable
internal fun CandlestickChart(bars: List<Candle>, wide: Boolean, period:ChartPeriod=ChartPeriod.DAY, dailyBars:List<Candle> = bars, funds:StockFundSeries?=null) {
    if (bars.isEmpty()) return
    var count by remember { mutableStateOf(if (wide) 60 else 30) }
    var end by remember(bars) { mutableStateOf(bars.size) }
    var selected by remember(bars) { mutableStateOf(bars.lastIndex) }
    var showMA by remember { mutableStateOf(true) }
    val periods=if(period==ChartPeriod.DAY) DAILY_MA_PERIODS else listOf(5,10,20)
    var enabledMA by remember(period) { mutableStateOf(periods.toSet()) }
    var showMacd by remember { mutableStateOf(true) }
    var showFunds by remember { mutableStateOf(true) }
    val visibleCount = min(count,bars.size)
    val stop = end.coerceIn(visibleCount,bars.size)
    val start = stop-visibleCount
    val visible = bars.subList(start,stop)
    val index = selected.coerceIn(start,stop-1)
    val candle = bars[index]
    val maColors = listOf(Color(0xFFAD761F),Color(0xFF397DC0),Color(0xFF9160AD),Color(0xFF258C85),Color(0xFFB75578),Color(0xFF657831),Color(0xFF3C4556))
    val ma = remember(bars,periods) { movingAverages(bars,periods) }
    val macd=remember(bars) { calculateMacd(bars) }
    val values = visible.flatMap { listOf(it.high,it.low) } + if (showMA) ma.filterIndexed { i,_ -> periods[i] in enabledMA }.flatMap { it.subList(start,stop).filterNotNull() } else emptyList()
    val (low,high) = chartPriceBounds(values)
    val selectedChange = bars.getOrNull(index-1)?.close?.takeIf { it>0 }?.let { (candle.close/it-1)*100 }
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color=Muted,fontSize=10.sp,fontFamily=FontFamily.Monospace)
    fun move(delta: Int) { end=(stop+delta).coerceIn(visibleCount,bars.size);selected=end-1 }

    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(candle.date, fontSize=13.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("candle-date"))
            Text(if (index==bars.lastIndex) "最新周期" else "历史周期 · ${index+1} / ${bars.size}",fontSize=10.sp,color=Muted)
        }
        Text(pct(selectedChange),color=color(selectedChange),fontSize=13.sp,fontFamily=FontFamily.Monospace)
        IconButton(onClick={count=(count-15).coerceAtLeast(15)},enabled=count>15) { Icon(Icons.Outlined.Add,"放大K线",Modifier.size(18.dp)) }
        IconButton(onClick={count=(count+15).coerceAtMost(120)},enabled=count<min(120,bars.size)) { Icon(Icons.Outlined.Remove,"缩小K线",Modifier.size(18.dp)) }
    }
    Row(Modifier.fillMaxWidth().background(Paper,RoundedCornerShape(8.dp)).padding(horizontal=10.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        listOf("开" to candle.open,"高" to candle.high,"低" to candle.low,"收" to candle.close).forEach { (label,value) ->
            Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(label,fontSize=10.sp,color=Muted)
                Text(decimal(value),fontSize=12.sp,fontWeight=FontWeight.Medium,fontFamily=FontFamily.Monospace,color=if(candle.close>=candle.open) Rise else Fall)
            }
        }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            periods.indices.toList().chunked(if(wide) 7 else 4).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    row.forEach { i ->
                        val active=showMA && periods[i] in enabledMA
                        Surface(onClick={enabledMA=if(periods[i] in enabledMA) enabledMA-periods[i] else enabledMA+periods[i]},color=if(active) maColors[i].copy(alpha=.07f) else Paper,shape=RoundedCornerShape(5.dp),modifier=Modifier.weight(1f).testTag("ma-${periods[i]}")) {
                            Column(Modifier.padding(horizontal=4.dp,vertical=6.dp)) {
                                Text("MA${periods[i]}",fontSize=10.sp,color=if(active) maColors[i] else Muted)
                                Text(if(active) decimal(ma[i][index]) else "—",fontSize=10.sp,color=if(active) maColors[i] else Muted,fontFamily=FontFamily.Monospace)
                            }
                        }
                    }
                    repeat((if(wide) 7 else 4)-row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        IconToggleButton(checked=showMA,onCheckedChange={showMA=it},modifier=Modifier.testTag("toggle-ma")) {
            Icon(if(showMA) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,"显示或隐藏均线",Modifier.size(19.dp),tint=if(showMA) Ink else Muted)
        }
    }
    Text(if(period==ChartPeriod.DAY) "日均线 · 点选单条显隐 · 不足对应日数显示 —" else "均线按当前${if(period==ChartPeriod.WEEK) "周" else "月"}周期计算，非日均线",fontSize=10.sp,color=Muted)

    Canvas(Modifier.fillMaxWidth().height(if(wide) 390.dp else 306.dp).testTag("candlestick-chart")
        .semantics { contentDescription="K线图，${candle.date}，开盘${decimal(candle.open)}，最高${decimal(candle.high)}，最低${decimal(candle.low)}，收盘${decimal(candle.close)}，成交量${quantity(candle.volume)}" }
        .pointerInput(start,stop) {
            val axis = 58.dp.toPx()
            detectTapGestures { p -> if(p.x<size.width-axis) selected=start+(p.x/(size.width-axis)*visibleCount).toInt().coerceIn(0,visibleCount-1) }
        }.pointerInput(start,stop) {
            val axis = 58.dp.toPx()
            detectHorizontalDragGestures { change,_ ->
                change.consume()
                selected=start+(change.position.x/(size.width-axis)*visibleCount).toInt().coerceIn(0,visibleCount-1)
            }
        }) {
        val right = size.width-58.dp.toPx()
        val top = 10.dp.toPx()
        val bottom = size.height-22.dp.toPx()
        val priceBottom = bottom-86.dp.toPx()
        val volumeTop = priceBottom+28.dp.toPx()
        val step=right/visibleCount
        fun y(v: Double)=top+((high-v)/(high-low)*(priceBottom-top)).toFloat()
        fun label(text: String,x: Float,yy: Float,tint: Color=Muted) {
            drawText(measurer,text,Offset(x,yy),style=axisStyle.copy(color=tint))
        }
        val dashed=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),4.dp.toPx()))
        repeat(5) { i ->
            val price=high-(high-low)*i/4
            val yy=y(price)
            drawLine(Line,Offset(0f,yy),Offset(right,yy),strokeWidth=1.dp.toPx())
            label(decimal(price),right+7.dp.toPx(),yy-6.dp.toPx())
        }
        repeat(4) { i -> val x=right*i/3;drawLine(Line.copy(alpha=.6f),Offset(x,top),Offset(x,bottom),pathEffect=dashed) }
        clipRect(0f,top,right,priceBottom) {
            visible.forEachIndexed { i,b ->
                val tint=if(b.close>=b.open) Rise else Fall
                val x=(i+.5f)*step
                val bodyWidth=(step*.62f).coerceAtLeast(1.dp.toPx())
                drawLine(tint,Offset(x,y(b.high)),Offset(x,y(b.low)),strokeWidth=1.dp.toPx())
                drawRect(tint,Offset(x-bodyWidth/2,min(y(b.open),y(b.close))),Size(bodyWidth,max(1.dp.toPx(),abs(y(b.open)-y(b.close)))))
            }
            if(showMA) ma.forEachIndexed { i,points ->
                if(periods[i] !in enabledMA) return@forEachIndexed
                val path=Path();var began=false
                for (j in start until stop) { val value=points[j] ?: continue;val x=(j-start+.5f)*step
                    if(!began) { path.moveTo(x,y(value));began=true } else path.lineTo(x,y(value))
                }
                drawPath(path,maColors[i],style=Stroke(1.3.dp.toPx()))
            }
            val last=bars.last()
            if(stop==bars.size) drawLine(color(last.change).copy(alpha=.6f),Offset(0f,y(last.close)),Offset(right,y(last.close)),pathEffect=dashed)
        }
        label("VOL ${quantity(candle.volume,volumeLabel(candle.volumeUnit))}",0f,priceBottom+9.dp.toPx())
        val maxVolume=visible.mapNotNull { it.volume }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        drawLine(Line,Offset(0f,bottom),Offset(right,bottom))
        visible.forEachIndexed { i,b ->
            val volume=b.volume
            if(volume==null) drawCircle(Muted,1.5.dp.toPx(),Offset((i+.5f)*step,bottom-2.dp.toPx()))
            else { val h=(volume/maxVolume*(bottom-volumeTop)).toFloat()
                if(h>0) drawRect((if(b.close>=b.open) Rise else Fall).copy(alpha=.68f),Offset((i+.19f)*step,bottom-h),Size(step*.62f,h))
            }
        }
        label(quantity(visible.mapNotNull { it.volume }.maxOrNull()),right+7.dp.toPx(),volumeTop-5.dp.toPx())
        label("0",right+7.dp.toPx(),bottom-10.dp.toPx())
        val crossX=(index-start+.5f)*step
        val crossY=y(candle.close)
        drawLine(Ink.copy(alpha=.65f),Offset(crossX,top),Offset(crossX,bottom),pathEffect=dashed)
        drawLine(Ink.copy(alpha=.65f),Offset(0f,crossY),Offset(right,crossY),pathEffect=dashed)
        drawCircle(Ink,3.dp.toPx(),Offset(crossX,crossY))
        drawRect(Ink,Offset(right,crossY-9.dp.toPx()),Size(58.dp.toPx(),18.dp.toPx()))
        label(decimal(candle.close),right+6.dp.toPx(),crossY-6.dp.toPx(),Color.White)
        label(visible.first().date.drop(2),0f,bottom+7.dp.toPx())
        val lastDate=visible.last().date.drop(2)
        val dateWidth=measurer.measure(lastDate,axisStyle).size.width.toFloat()
        label(lastDate,(right-dateWidth).coerceAtLeast(0f),bottom+7.dp.toPx())
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(showMacd,{showMacd=!showMacd},label={Text("MACD",fontSize=12.sp)},modifier=Modifier.testTag("toggle-macd"))
        FilterChip(showFunds,{showFunds=!showFunds},label={Text("主力资金",fontSize=12.sp)},modifier=Modifier.testTag("toggle-funds"))
    }
    if(showMacd) MacdPane(bars,macd,start,stop,index) { selected=it }
    if(showFunds) StockFundsPane(bars,dailyBars,period,funds,start,stop,index) { selected=it }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        IconButton(onClick={move(-max(1,visibleCount/2))},enabled=start>0,modifier=Modifier.testTag("history-previous")) { Icon(Icons.Outlined.ChevronLeft,"更早行情") }
        Text("${start+1}–$stop / ${bars.size} 根",Modifier.weight(1f),fontSize=11.sp,color=Muted)
        IconButton(onClick={move(max(1,visibleCount/2))},enabled=stop<bars.size) { Icon(Icons.Outlined.ChevronRight,"较新行情") }
        TextButton(onClick={end=bars.size;selected=bars.lastIndex},enabled=stop<bars.size || index<bars.lastIndex,modifier=Modifier.testTag("history-latest")) { Text("最新",fontSize=12.sp) }
    }
    if(bars.size>visibleCount) Slider(stop.toFloat(),{end=it.roundToInt();selected=end-1},valueRange=visibleCount.toFloat()..bars.size.toFloat(),modifier=Modifier.fillMaxWidth().height(24.dp).testTag("history-slider"))
    Text("点选 / 横向拖动查看 · ＋ / − 缩放 · 下方滑块浏览历史",fontSize=10.sp,color=Muted)
}
