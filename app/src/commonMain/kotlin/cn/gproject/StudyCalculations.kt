package cn.gproject

import kotlin.math.*

internal data class KdjPoint(val k: Double, val d: Double, val j: Double)
internal data class BollPoint(val middle: Double, val upper: Double, val lower: Double)

/** KDJ(9,3,3): first 9-bar RSV, K/D start at 50; J is deliberately not clipped. */
internal fun calculateKdj(bars: List<Candle>): List<KdjPoint?> {
    movingAverages(bars, emptyList()) // common validation of ordered, finite closes
    require(bars.all { it.high.isFinite() && it.low.isFinite() && it.low > 0 && it.high >= it.close && it.low <= it.close })
    var k=50.0;var d=50.0
    return bars.indices.map { i ->
        if(i<8) null else {
            val window=bars.subList(i-8,i+1);val low=window.minOf { it.low };val high=window.maxOf { it.high }
            val rsv=if(high==low) 50.0 else (bars[i].close-low)/(high-low)*100
            k=(2*k+rsv)/3;d=(2*d+k)/3
            KdjPoint(k,d,3*k-2*d)
        }
    }
}

/** Wilder RSI(14): first 14 deltas form the SMA seed. Flat series = 50. */
internal fun calculateRsi(bars: List<Candle>, period: Int = 14): List<Double?> {
    require(period>0);movingAverages(bars,emptyList())
    val result=MutableList<Double?>(bars.size) { null }
    if(bars.size<=period) return result
    var gain=0.0;var loss=0.0
    for(i in 1..period) { val change=bars[i].close-bars[i-1].close;gain+=max(0.0,change)/period;loss+=max(0.0,-change)/period }
    fun value()=when { gain==0.0 && loss==0.0 -> 50.0;loss==0.0 -> 100.0;else -> 100.0-100.0/(1+gain/loss) }
    result[period]=value()
    for(i in period+1 until bars.size) {
        val change=bars[i].close-bars[i-1].close
        gain=(gain*(period-1)+max(0.0,change))/period;loss=(loss*(period-1)+max(0.0,-change))/period
        result[i]=value()
    }
    return result
}

/** BOLL(20,2): SMA +/- twice the population standard deviation, inclusive of current bar. */
internal fun calculateBoll(bars: List<Candle>): List<BollPoint?> {
    val average=movingAverages(bars,listOf(20)).single()
    return bars.indices.map { i -> average[i]?.let { mean ->
        val deviation=sqrt((i-19..i).sumOf { (bars[it].close-mean).pow(2) }/20)
        BollPoint(mean,mean+2*deviation,mean-2*deviation)
    } }
}

/** Only comparable, same-provider/unit previous sessions; excludes current volume. */
internal fun priorVolumeRatio(bars: List<Candle>, index: Int): Double? {
    if(index<20) return null
    val current=bars[index];val window=bars.subList(index-20,index)
    val v=current.volume ?: return null
    if(!v.isFinite() || v<0 || window.any { it.volume==null || !it.volume.isFinite() || it.volume<0 || it.source!=current.source || it.volumeUnit!=current.volumeUnit }) return null
    val mean=window.sumOf { it.volume!!/20 }
    return if(mean>0) v/mean else null
}

internal data class DailyStudies(
    val ma: List<List<Double?>>,val macd: List<MacdPoint?>,val kdj: List<KdjPoint?>,
    val rsi: List<Double?>,val boll: List<BollPoint?>,
)

internal fun dailyStudies(bars: List<Candle>)=DailyStudies(movingAverages(bars,listOf(5,10,20,60)),calculateMacd(bars),calculateKdj(bars),calculateRsi(bars),calculateBoll(bars))

internal fun studyMeaning(kind: String,bars: List<Candle>,data: DailyStudies,index: Int): String {
    val bar=bars[index]
    return when(kind) {
        "volume" -> {
            val ratio=priorVolumeRatio(bars,index)
            when { bar.volume==null -> "本日成交量缺失，不能判断放量或缩量。"
                ratio==null -> "以原始成交量观察活跃度；缺少前20个同口径交易日，暂不判断放量或缩量。"
                ratio>=1.5 -> "成交量为此前20日均量的 ${decimal(ratio)} 倍，交易活跃度明显上升；放量本身不代表价格会继续上涨。"
                ratio<=.7 -> "成交量为此前20日均量的 ${decimal(ratio)} 倍，交易活跃度收缩，价格变化的参与度较低。"
                else -> "成交量为此前20日均量的 ${decimal(ratio)} 倍，接近近期常态，未出现明显放量或缩量。" }
        }
        "ma" -> {
            val values=data.ma.take(3).map { it[index] }
            if(values.any { it==null }) "MA5/10/20 尚未完成预热，需要至少20个交易日。"
            else {
                val (a,b,c)=values.map { it!! }
                when { a>b && b>c -> "MA5 > MA10 > MA20，短中期均线呈多头排列，反映近期价格趋势偏强。"
                    a<b && b<c -> "MA5 < MA10 < MA20，短中期均线呈空头排列，反映近期价格趋势偏弱。"
                    else -> "短中期均线交错，价格方向尚未形成一致趋势。" }
            }
        }
        "macd" -> {
            val p=data.macd[index]
            if(p==null) "MACD(12,26,9) 尚在预热，至少需要34根日线；缺值不视作零。"
            else {
                val prior=data.macd.getOrNull(index-1)
                val cross=when { prior==null -> "";prior.dif<=prior.dea && p.dif>p.dea -> "DIF 刚上穿 DEA（金叉）；";prior.dif>=prior.dea && p.dif<p.dea -> "DIF 刚下穿 DEA（死叉）；";else -> "" }
                cross+when { abs(p.dif)<1e-9 && abs(p.dea)<1e-9 -> "双线接近零轴，当前趋势动量较平衡。";p.dif>0 && p.dea>0 -> "双线在零轴上方，中期动量偏强。";p.dif<0 && p.dea<0 -> "双线在零轴下方，中期动量偏弱。";else -> "双线在零轴附近分化，趋势可能处于切换。" }+
                    when { prior==null -> "";abs(p.histogram-prior.histogram)<1e-9 -> "柱值与前日接近。"
                        p.histogram>=0 && p.histogram>prior.histogram -> "正柱增强或刚转正，上行动量改善。"
                        p.histogram>=0 -> "正柱缩短，上行动量减弱。"
                        p.histogram<prior.histogram -> "负柱扩大，下行压力增加。"
                        else -> "负柱缩短，下行压力减弱。" }
            }
        }
        "kdj" -> {
            val p=data.kdj[index]
            if(p==null) "KDJ(9,3,3) 需要9根日线，目前数据不足。"
            else {
                val prior=data.kdj.getOrNull(index-1)
                val cross=when { prior==null -> "";prior.k<=prior.d && p.k>p.d -> "K 刚上穿 D；";prior.k>=prior.d && p.k<p.d -> "K 刚下穿 D；";else -> "" }
                cross+when { p.k>=80 && p.d>=80 -> "K、D 均处于高位，价格靠近近期区间上部；强趋势中可持续高位钝化。";p.k<=20 && p.d<=20 -> "K、D 均处于低位，价格靠近近期区间下部；低位不等于已经见底。";else -> "K、D 未同时进入高位或低位区，观察短期摆动方向；J 会超出0—100，无需截断。" }
            }
        }
        "rsi" -> data.rsi[index]?.let { when { it>=70 -> "RSI14 达到高位，近期上涨动量较强，同时短期拥挤度上升；高位可持续。";it<=30 -> "RSI14 处于低位，近期下跌动量较强，不能据此确认反转。";it>50 -> "RSI14 位于50上方，近期上涨幅度相对占优。";it<50 -> "RSI14 位于50下方，近期下跌幅度相对占优。";else -> "RSI14 接近50，多空动量相对均衡。" } } ?: "RSI14 需要15个收盘价，目前数据不足。"
        "boll" -> data.boll[index]?.let { p -> when { p.upper==p.lower -> "近20日价格波动很小，布林带收敛；不能据此判断突破方向。";bar.close>p.upper -> "收盘价位于上轨之外，价格相对20日均值偏高，可能延续趋势或回归区间。";bar.close<p.lower -> "收盘价位于下轨之外，价格相对20日均值偏低，不等于确认买点。";bar.close>=p.middle -> "收盘价在中轨与上轨之间，价格处于近期区间偏强一侧。";else -> "收盘价在下轨与中轨之间，价格处于近期区间偏弱一侧。" } } ?: "BOLL(20,2) 需要20根日线，目前数据不足。"
        else -> error("unknown study")
    }
}
