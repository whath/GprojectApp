package cn.gproject

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

internal data class ChartWindow(val start: Int, val span: Int)
internal fun chartWindow(total: Int, start: Int, span: Int, zoom: Float = 1f, pan: Float = 0f, anchor: Float = .5f): ChartWindow {
    require(total>0 && zoom.isFinite() && zoom>0 && pan.isFinite() && anchor.isFinite())
    val oldSpan=span.coerceIn(min(5,total),total)
    val nextSpan=(oldSpan/zoom).roundToInt().coerceIn(min(5,total),total)
    val nextStart=(start+(oldSpan-nextSpan)*anchor.coerceIn(0f,1f)-pan*oldSpan).roundToInt().coerceIn(0,total-nextSpan)
    return ChartWindow(nextStart,nextSpan)
}

@Composable
internal fun RevisionChart(detail: DailyDetail) {
    var minute by rememberSaveable(detail.asset.id) { mutableStateOf(false) }
    var dailySpan by rememberSaveable(detail.asset.id,detail.candles.size) { mutableStateOf(min(90,detail.candles.size)) }
    var dailyStart by rememberSaveable(detail.asset.id,detail.candles.size) { mutableStateOf(max(0,detail.candles.size-dailySpan)) }
    val snapshot=detail.intraday
    var minuteSpan by rememberSaveable(detail.asset.id,snapshot?.date) { mutableStateOf(snapshot?.points?.size ?: 1) }
    var minuteStart by rememberSaveable(detail.asset.id,snapshot?.date) { mutableStateOf(0) }
    val total=if(minute) snapshot?.points?.size ?: 0 else detail.candles.size
    val start=if(minute) minuteStart else dailyStart
    val span=if(minute) minuteSpan else dailySpan
    fun setWindow(window: ChartWindow) {
        if(minute) { minuteStart=window.start;minuteSpan=window.span }
        else { dailyStart=window.start;dailySpan=window.span }
    }
    fun preset(count: Int) { minute=false;dailySpan=min(count,detail.candles.size);dailyStart=detail.candles.size-dailySpan }
    Text(if(minute) "收盘分时快照" else if(detail.historyWeeks==52) "近 52 周日线" else "近 30 个交易日日线",fontSize=17.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        if(snapshot!=null) FilterChip(minute,{minute=true},label={Text("分时")},modifier=Modifier.testTag("chart-minute"))
        FilterChip(!minute && dailySpan<=30,{preset(30)},label={Text("近30日")})
        if(detail.historyWeeks==52) {
            FilterChip(!minute && dailySpan in 31..90,{preset(90)},label={Text("近90日")})
            FilterChip(!minute && dailySpan==detail.candles.size,{preset(detail.candles.size)},label={Text("52周")},modifier=Modifier.testTag("chart-52w"))
        }
    }
    if(minute && snapshot?.status=="not_traded") Text(snapshot.note,fontSize=12.sp,color=Muted)
    else if(total>0) {
        Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            OutlinedButton(onClick={setWindow(chartWindow(total,start,span,pan=.6f))},enabled=start>0,modifier=Modifier.testTag("chart-earlier"),contentPadding=PaddingValues(horizontal=12.dp)) { Text("←") }
            OutlinedButton(onClick={setWindow(chartWindow(total,start,span,zoom=.7f))},enabled=span<total,modifier=Modifier.testTag("chart-zoom-out"),contentPadding=PaddingValues(horizontal=12.dp)) { Text("−") }
            OutlinedButton(onClick={setWindow(chartWindow(total,start,span,zoom=1.4f))},enabled=span>min(5,total),modifier=Modifier.testTag("chart-zoom-in"),contentPadding=PaddingValues(horizontal=12.dp)) { Text("＋") }
            OutlinedButton(onClick={setWindow(chartWindow(total,start,span,pan=-.6f))},enabled=start+span<total,modifier=Modifier.testTag("chart-later"),contentPadding=PaddingValues(horizontal=12.dp)) { Text("→") }
        }
        val gesture=Modifier.pointerInput(minute,total) {
            var pendingPan=0f
            detectTransformGestures { centroid,pan,zoom,_ ->
                pendingPan+=pan.x/size.width
                if(zoom!=1f || abs(pendingPan)*span>=.6f) {
                    setWindow(chartWindow(total,if(minute) minuteStart else dailyStart,if(minute) minuteSpan else dailySpan,
                        zoom,pendingPan,centroid.x/size.width));pendingPan=0f
                }
            }
        }
        val safe=chartWindow(total,start,span)
        if(minute) {
            val shown=snapshot!!.points.subList(safe.start,safe.start+safe.span)
            var selected by remember(shown) { mutableStateOf(shown.lastIndex) }
            Canvas(Modifier.fillMaxWidth().height(225.dp).testTag("daily-intraday").then(gesture).pointerInput(shown) {
                detectTapGestures { selected=(it.x/size.width*shown.size).toInt().coerceIn(shown.indices) }
            }) {
                val bounds=chartPriceBounds(shown.map { it.close });val range=bounds.second-bounds.first
                val path=Path()
                shown.forEachIndexed { i,point ->
                    val x=i*size.width/max(shown.lastIndex,1);val y=(size.height*(1-(point.close-bounds.first)/range)).toFloat()
                    if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                }
                drawPath(path,Fall,style=Stroke(2.dp.toPx()))
                val x=selected.coerceIn(shown.indices)*size.width/max(shown.lastIndex,1)
                drawLine(Muted.copy(alpha=.4f),Offset(x,0f),Offset(x,size.height))
            }
            val point=shown[selected.coerceIn(shown.indices)]
            Text("${point.timestamp.substring(11,16)}  ${decimal(point.close)}",fontSize=11.sp,color=Muted)
            Text("${snapshot.date} · ${shown.first().timestamp.substring(11,16)} — ${shown.last().timestamp.substring(11,16)} · ${shown.size} 个分钟观测点",fontSize=10.sp,color=Muted,modifier=Modifier.testTag("chart-range"))
            Text("腾讯行情 · 已与当日日线收盘核对；收盘快照，不是实时行情。",fontSize=10.sp,color=Muted)
        } else {
            val shown=detail.candles.subList(safe.start,safe.start+safe.span)
            CompactCandles(shown,gesture,if(detail.asset.id.startsWith("CN.etf.")) 3 else 2)
            Text("${shown.first().date} — ${shown.last().date} · ${shown.size} 日",fontSize=10.sp,color=Muted,modifier=Modifier.testTag("chart-range"))
            if(!detail.complete) Text("窗口内存在未验证交易日，请结合已返回日期查看。",fontSize=11.sp,color=Muted)
            Text("不复权价格 · 双指缩放、横拖平移；桌面可用上方按钮。",fontSize=10.sp,color=Muted)
        }
    }
    if(detail.intradayError.isNotBlank()) Text(detail.intradayError,fontSize=11.sp,color=Muted)
}
