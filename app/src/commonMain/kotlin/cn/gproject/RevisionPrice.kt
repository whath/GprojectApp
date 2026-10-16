package cn.gproject

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

internal fun pricePlotIndex(x: Float,width: Float,count: Int): Int {
    require(width>0 && count>0)
    return (x/width*count).toInt().coerceIn(0,count-1)
}

private fun DrawScope.priceAxis(bounds: Pair<Double,Double>,right: Float,digits: Int,measure: TextMeasurer) {
    val style=TextStyle(color=Muted,fontSize=9.sp)
    for(i in 0..4) {
        val fraction=i/4f;val y=8.dp.toPx()+(size.height-16.dp.toPx())*fraction
        val value=bounds.second-(bounds.second-bounds.first)*fraction
        drawLine(Line,Offset(0f,y),Offset(right,y))
        drawText(measure,decimal(value,digits),Offset(right+4.dp.toPx(),(y-5.dp.toPx()).coerceIn(0f,size.height-12.dp.toPx())),style)
    }
}

@Composable
internal fun CompactCandles(bars: List<Candle>,gesture: Modifier = Modifier,digits: Int = 2,selected: Int = bars.lastIndex,
    overlays: List<List<Double?>> = emptyList(),onSelect: (Int)->Unit = {}) {
    val index=selected.coerceIn(bars.indices);val focused=bars[index];val measure=rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(225.dp).testTag("daily-candles")
        .semantics { contentDescription="价格轴；${focused.date} 收盘 ${decimal(focused.close,digits)}" }.then(gesture)
        .pointerInput(bars) { val axis=58.dp.toPx();detectTapGestures { p -> if(p.x<size.width-axis) onSelect(pricePlotIndex(p.x,(size.width-axis).toFloat(),bars.size)) } }) {
        val right=(size.width-58.dp.toPx()).coerceAtLeast(1f)
        val bounds=chartPriceBounds(bars.flatMap { listOf(it.low,it.high) }+overlays.flatMap { it.filterNotNull() })
        val top=8.dp.toPx();val height=size.height-16.dp.toPx();val step=right/bars.size
        fun y(price: Double)=top+(height*(1-(price-bounds.first)/(bounds.second-bounds.first))).toFloat()
        priceAxis(bounds,right,digits,measure)
        bars.forEachIndexed { i,bar ->
            val x=(i+.5f)*step;val tint=if(bar.close>=bar.open) Rise else Fall
            drawLine(tint,Offset(x,y(bar.high)),Offset(x,y(bar.low)),1.dp.toPx())
            drawRect(tint,Offset(x-step*.28f,min(y(bar.open),y(bar.close))),Size(step*.56f,max(abs(y(bar.close)-y(bar.open)),1.5.dp.toPx())))
        }
        overlays.forEachIndexed { n,values ->
            val path=Path();var begun=false
            values.forEachIndexed { i,v -> if(v==null) begun=false else {
                val x=(i+.5f)*step;if(begun) path.lineTo(x,y(v)) else path.moveTo(x,y(v));begun=true
            } }
            drawPath(path,StudyColors[n],style=Stroke(1.dp.toPx()))
        }
        val x=(index+.5f)*step
        drawLine(Muted.copy(alpha=.6f),Offset(x,top),Offset(x,top+height))
        drawLine(Muted.copy(alpha=.6f),Offset(0f,y(focused.close)),Offset(right,y(focused.close)))
    }
    Text("${focused.date}  开 ${decimal(focused.open,digits)}  高 ${decimal(focused.high,digits)}\n低 ${decimal(focused.low,digits)}  收 ${decimal(focused.close,digits)}",fontSize=11.sp,color=Muted,lineHeight=18.sp,modifier=Modifier.testTag("chart-selected-price"))
    Text("价格轴与十字线 · 金 MA5 / 蓝 MA10 / 紫 MA20",fontSize=10.sp,color=Muted)
}

@Composable
internal fun MinutePrice(points: List<SnapshotMinutePoint>,selected: Int,gesture: Modifier,onSelect: (Int)->Unit) {
    val measure=rememberTextMeasurer();val focus=points[selected]
    Canvas(Modifier.fillMaxWidth().height(225.dp).testTag("daily-intraday")
        .semantics { contentDescription="分时价格轴；${focus.timestamp.substring(11,16)} ${decimal(focus.close)}" }.then(gesture)
        .pointerInput(points) { val axis=58.dp.toPx();detectTapGestures { p -> if(p.x<size.width-axis) onSelect(pricePlotIndex(p.x,(size.width-axis).toFloat(),points.size)) } }) {
        val right=(size.width-58.dp.toPx()).coerceAtLeast(1f);val bounds=chartPriceBounds(points.map { it.close });val top=8.dp.toPx();val height=size.height-16.dp.toPx()
        fun y(price: Double)=top+(height*(1-(price-bounds.first)/(bounds.second-bounds.first))).toFloat()
        priceAxis(bounds,right,2,measure)
        val step=right/points.size;val path=Path()
        points.forEachIndexed { i,point -> val x=(i+.5f)*step;if(i==0) path.moveTo(x,y(point.close)) else path.lineTo(x,y(point.close)) }
        drawPath(path,Fall,style=Stroke(2.dp.toPx()))
        val x=(selected+.5f)*step
        drawLine(Muted.copy(alpha=.5f),Offset(x,top),Offset(x,top+height));drawLine(Muted.copy(alpha=.5f),Offset(0f,y(focus.close)),Offset(right,y(focus.close)))
    }
    Text("${focus.timestamp.substring(11,16)}  价格 ${decimal(focus.close)}",fontSize=11.sp,color=Muted,modifier=Modifier.testTag("chart-selected-price"))
}
