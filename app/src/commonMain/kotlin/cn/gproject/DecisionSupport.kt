package cn.gproject

internal data class FlowAttention(val label:String,val needsReview:Boolean=false,val usable:Boolean=false)

internal fun flowAttention(series:FlowSeries?,targetDate:String,days:Int,demo:Boolean):FlowAttention {
    if(series==null || series.rows.isEmpty()) return FlowAttention("资金待接入")
    if(series.error!=null) return FlowAttention("资金更新失败")
    if(!demo && (targetDate.isBlank() || series.dates.lastOrNull()!=targetDate)) return FlowAttention("资金历史快照")
    val summary=summarizeFlows(series,days)
    if(summary.outflowStreak>=3) return FlowAttention("连续 ${summary.outflowStreak} 日净流出",true,true)
    if(summary.net==null) return FlowAttention("资金覆盖 ${summary.covered}/$days 日")
    return FlowAttention("${days}日净额 ${money(summary.net)}",usable=true)
}

internal fun reviewBoards(state:ScreenState):List<Board> = (state.monitor.settings.watched + state.workspace.context.associations.values.flatten()).distinctBy { it.id }

internal fun orderedReviewSetups(hits:List<SetupHit>)=hits.sortedBy { if(it.preset==StrategyPreset.TOP_RISK || it.preset==StrategyPreset.DOWNTREND) 0 else 1 }

internal fun scanReferenceDate(state:ScreenState):String = listOf(state.date,state.monitor.targetDate).filter { runCatching { kotlinx.datetime.LocalDate.parse(it) }.isSuccess }.maxOrNull().orEmpty()
internal fun historicalScan(state:ScreenState):Boolean = !state.demo && state.scanProgress?.date?.let { scanReferenceDate(state).isNotBlank() && it<scanReferenceDate(state) }==true
