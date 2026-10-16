package cn.gproject

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

internal val StudyColors=listOf(Color(0xFFAC721F),Color(0xFF426CA2),Color(0xFF9263A5),Color(0xFF598449))

/** All series use full-history indices; changing the viewport cannot restart EMA/RSI. */
@Composable
internal fun DailyStudyPanels(bars: List<Candle>,data: DailyStudies,start: Int,stop: Int,index: Int,onSelect: (Int)->Unit) {
    val bar=bars[index]
    Text("指标基于不复权日线，截至所选 ${bar.date}；历史数据不足时留空。除权除息可能产生跳变。",fontSize=10.sp,color=Muted)
    fun label(kind: String)=studyMeaning(kind,bars,data,index)
    StudyHeading("成交量",bar.volume?.let { compactVolume(it) } ?: "暂无", "volume")
    StudyPlot(bars.map { it.volume },emptyList(),start,stop,index,"study-volume",bars.map { if(it.close>=it.open) Rise else Fall },zeroFloor=true,onSelect=onSelect)
    Text(volumeUnitLabel(bar.volumeUnit),fontSize=10.sp,color=Muted)
    StudyInterpretation("volume",bar.date,label("volume"))
    val maLabels=listOf(5,10,20,60).mapIndexedNotNull { i,p -> data.ma[i][index]?.let { "MA$p ${decimal(it)}" } }
    StudyHeading("均线",maLabels.joinToString(" · "),"ma")
    StudyInterpretation("ma",bar.date,label("ma"))
    val macd=data.macd[index]
    StudyHeading("MACD(12,26,9)",macd?.let { "DIF ${decimal(it.dif,3)} · DEA ${decimal(it.dea,3)} · 柱 ${decimal(it.histogram,3)}" } ?: "预热不足34日","macd")
    StudyPlot(data.macd.map { it?.histogram },listOf(data.macd.map { it?.dif } to StudyColors[0],data.macd.map { it?.dea } to StudyColors[1]),start,stop,index,"study-macd",symmetric=true,onSelect=onSelect)
    Text("金线 DIF / 蓝线 DEA；柱值为 2×(DIF−DEA)。",fontSize=10.sp,color=Muted)
    StudyInterpretation("macd",bar.date,label("macd"))
    val kdj=data.kdj[index]
    StudyHeading("KDJ(9,3,3)",kdj?.let { "K ${decimal(it.k)} · D ${decimal(it.d)} · J ${decimal(it.j)}" } ?: "预热不足9日","kdj")
    StudyPlot(emptyList(),listOf(data.kdj.map { it?.k } to StudyColors[0],data.kdj.map { it?.d } to StudyColors[1],data.kdj.map { it?.j } to StudyColors[2]),start,stop,index,"study-kdj",guides=listOf(20.0,80.0),onSelect=onSelect)
    Text("金线 K / 蓝线 D / 紫线 J；参考线20、80，J不截断。",fontSize=10.sp,color=Muted)
    StudyInterpretation("kdj",bar.date,label("kdj"))
    StudyHeading("RSI(14)",data.rsi[index]?.let { decimal(it) } ?: "预热不足15日","rsi")
    StudyPlot(emptyList(),listOf(data.rsi to StudyColors[2]),start,stop,index,"study-rsi",fixedRange=0.0 to 100.0,guides=listOf(30.0,70.0),onSelect=onSelect)
    StudyInterpretation("rsi",bar.date,label("rsi"))
    val boll=data.boll[index]
    StudyHeading("BOLL(20,2)",boll?.let { "上 ${decimal(it.upper)} · 中 ${decimal(it.middle)} · 下 ${decimal(it.lower)}" } ?: "预热不足20日","boll")
    StudyPlot(emptyList(),listOf(data.boll.map { it?.upper } to StudyColors[0],data.boll.map { it?.middle } to StudyColors[1],data.boll.map { it?.lower } to StudyColors[2],bars.map { it.close } to Ink),start,stop,index,"study-boll",onSelect=onSelect)
    Text("黑线为收盘价；金 / 蓝 / 紫依次为上 / 中 / 下轨。",fontSize=10.sp,color=Muted)
    StudyInterpretation("boll",bar.date,label("boll"))
    Text("形态解释用于理解已有行情，不能单独确认买卖时点。MACD 使用 SMA 初始值，柱值为 2×(DIF−DEA)；KDJ 初始 K/D=50；RSI 使用 Wilder 平滑；BOLL 为20日总体标准差的2倍。",fontSize=10.sp,color=Muted,lineHeight=17.sp)
}

@Composable
private fun StudyHeading(title: String,values: String,kind: String) {
    HorizontalDivider(color=Line,modifier=Modifier.padding(top=10.dp))
    Text(title,fontSize=14.sp,modifier=Modifier.testTag("study-heading-$kind"))
    if(values.isNotBlank()) Text(values,fontSize=10.sp,color=Muted,modifier=Modifier.testTag("study-values-$kind"))
}

@Composable
private fun StudyInterpretation(kind: String,date: String,text: String) {
    Text("$date · $text",fontSize=11.sp,color=Muted,lineHeight=19.sp,modifier=Modifier.testTag("study-meaning-$kind"))
}

internal fun compactVolume(value: Double)=when { value>=100000000 -> decimal(value/100000000)+"亿";value>=10000 -> decimal(value/10000)+"万";else -> decimal(value,0) }
internal fun volumeUnitLabel(unit: String)=when(unit) { "share" -> "成交量单位：股";"lot" -> "成交量单位：手";else -> "成交量为来源原始量，股/手单位尚未核实；万/亿只作数量缩写。" }

@Composable
internal fun StudyPlot(histogram: List<Double?>,lines: List<Pair<List<Double?>,Color>>,start: Int,stop: Int,index: Int,tag: String,
    barColors: List<Color> = emptyList(),zeroFloor: Boolean = false,symmetric: Boolean = false,
    fixedRange: Pair<Double,Double>? = null,guides: List<Double> = emptyList(),onSelect: (Int)->Unit) {
    val values=histogram.slice(start until min(stop,histogram.size)).filterNotNull()+lines.flatMap { it.first.subList(start,stop).filterNotNull() }
    val range=when { fixedRange!=null -> fixedRange;symmetric -> (values.maxOfOrNull { abs(it) } ?: .001).coerceAtLeast(.001).let { -it*1.15 to it*1.15 }
        zeroFloor -> 0.0 to ((values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)*1.12)
        else -> chartPriceBounds(values+guides) }
    val measure=rememberTextMeasurer();val style=TextStyle(color=Muted,fontSize=9.sp)
    Canvas(Modifier.fillMaxWidth().height(if(zeroFloor) 85.dp else 108.dp).testTag(tag).semantics { contentDescription="$tag，点选同步日期与形态解释" }
        .pointerInput(start,stop) { val axis=58.dp.toPx();detectTapGestures { p -> if(p.x<size.width-axis) onSelect(start+(p.x/(size.width-axis)*(stop-start)).toInt().coerceIn(0,stop-start-1)) } }) {
        val right=(size.width-58.dp.toPx()).coerceAtLeast(1f);val top=8.dp.toPx();val bottom=size.height-8.dp.toPx()
        val step=right/(stop-start)
        fun y(value: Double)=top+((range.second-value)/(range.second-range.first)*(bottom-top)).toFloat()
        listOf(range.first,(range.first+range.second)/2,range.second).forEach { value ->
            drawLine(Line,Offset(0f,y(value)),Offset(right,y(value)))
            drawText(measure,if(zeroFloor) compactVolume(value) else decimal(value,if(symmetric) 3 else 2),Offset(right+4.dp.toPx(),y(value)-5.dp.toPx()),style)
        }
        guides.forEach { value -> drawLine(Muted.copy(alpha=.4f),Offset(0f,y(value)),Offset(right,y(value))) }
        if(symmetric) drawLine(Muted.copy(alpha=.5f),Offset(0f,y(0.0)),Offset(right,y(0.0)))
        for(i in start until min(stop,histogram.size)) histogram[i]?.let { value ->
            val x=(i-start+.5f)*step
            if(value!=0.0) drawRect(barColors.getOrNull(i) ?: color(value),Offset(x-step*.28f,min(y(0.0),y(value))),Size(step*.56f,max(1f,abs(y(value)-y(0.0)))))
        }
        lines.forEach { (series,tint) ->
            val path=Path();var begun=false
            for(i in start until stop) { val v=series[i];if(v==null) { begun=false;continue };val x=(i-start+.5f)*step
                if(begun) path.lineTo(x,y(v)) else path.moveTo(x,y(v));begun=true }
            drawPath(path,tint,style=Stroke(1.3.dp.toPx()))
        }
        val x=(index-start+.5f)*step;drawLine(Muted.copy(alpha=.5f),Offset(x,top),Offset(x,bottom))
    }
}
