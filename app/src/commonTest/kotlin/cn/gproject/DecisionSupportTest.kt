package cn.gproject

import kotlin.test.*

class DecisionSupportTest {
    @Test fun completedScanBecomesHistoricalWhenEitherReliableDateAdvances() {
        val state=ScreenState(date="2026-09-25",scanPhase=ScanPhase.COMPLETE,scanProgress=ScanProgress(20,20,20,0,"2026-09-25"))
        assertFalse(historicalScan(state))
        assertTrue(historicalScan(state.copy(monitor=MonitorState(targetDate="2026-09-28"))))
        assertFalse(historicalScan(state.copy(date="2026-09-28",scanProgress=state.scanProgress!!.copy(date="2026-09-28"))))
        assertFalse(historicalScan(state.copy(demo=true,date="2026-09-28")))
    }
    private val dates=(21..25).map { "2026-09-$it" }
    private fun series()=FlowSeries("board",dates,dates.map { FlowDay(it,null,null,-10.0) },"test","main","2026-09-25T08:00:00Z")
    @Test fun staleAndFailedFlowsDoNotBecomeActiveRiskAlerts() {
        assertTrue(flowAttention(series(),dates.last(),5,false).needsReview)
        assertFalse(flowAttention(series(),"2026-09-28",5,false).needsReview)
        assertFalse(flowAttention(series().copy(error="失败"),dates.last(),5,false).needsReview)
        assertFalse(flowAttention(series(),"",5,false).usable)
    }
    @Test fun MissingCoverageIsNotRepresentedAsNoRisk() {
        val incomplete=series().copy(rows=listOf(FlowDay(dates.last(),null,null,10.0)))
        assertEquals("资金覆盖 1/5 日",flowAttention(incomplete,dates.last(),5,false).label)
        assertFalse(flowAttention(incomplete,dates.last(),5,false).usable)
        assertEquals("资金待接入",flowAttention(null,dates.last(),5,false).label)
    }
    @Test fun reviewQueueIncludesPoolAssociationsWithoutDuplicates() {
        val board=Board("b","半导体",1.0)
        val state=ScreenState(monitor=MonitorState(settings=MonitorSettings(watched=listOf(board))),workspace=WorkspaceState(context=WatchContext(associations=mapOf("s" to listOf(board,Board("c","银行",2.0))))))
        assertEquals(listOf("b","c"),reviewBoards(state).map { it.id })
    }
}
