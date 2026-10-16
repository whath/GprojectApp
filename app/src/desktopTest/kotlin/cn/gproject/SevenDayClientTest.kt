package cn.gproject

import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SevenDayClientTest {
    private fun ranking(period: String="7d",adjustment: String="qfq")=Json.parseToJsonElement("""{"period":"$period","period_sessions":7,"trade_date":"2026-10-15","eligible_count":100,"expected_count":105,"missing_data_count":0,"items":[{"id":"CN.stock.000001","name":"平安银行","symbol":"000001","trade_date":"2026-10-15","source":"test","adjustment":"$adjustment","close":20,"change_7d_pct":10,"rank":1}]}""").jsonObject

    @Test fun sevenDaysAreRequiredAndUnadjustedReturnIsRejected() {
        assertEquals(7,parseDailyRanking(ranking()).items.single().periodSessions)
        assertFailsWith<IllegalArgumentException> { parseDailyRanking(ranking("5d")) }
        assertFailsWith<IllegalArgumentException> { parseDailyRanking(ranking(adjustment="none")) }
    }
    @Test fun rankingRequestsOnlyTheVisibleTopRatherThanEverySecurity(): Unit=runBlocking {
        val api=MarketRepository(HttpClient(MockEngine { request ->
            assertEquals("7d",request.url.parameters["period"])
            assertEquals("10",request.url.parameters["top"]);assertEquals("10",request.url.parameters["limit"])
            val row=ranking().toMutableMap();row["display_limit"]=JsonPrimitive(10);row["total"]=JsonPrimitive(1)
            val text=JsonObject(row).toString().replace("CN.stock.","CN.etf.")
            respond(text,HttpStatusCode.OK)
        }),requestSpacingMillis=0)
        try { api.base="https://example.test";assertEquals(1,api.dailyRanking("etf","2026-10-15").items.size) } finally { api.close() }
    }
    @Test fun zoomBoundsAndPanNeverEscapeTheAvailable52WeekWindow() {
        assertEquals(ChartWindow(0,241),chartWindow(241,0,241,zoom=.01f))
        assertEquals(5,chartWindow(241,0,30,zoom=100f).span)
        assertEquals(0,chartWindow(241,0,30,pan=100f).start)
        assertEquals(211,chartWindow(241,200,30,pan=-100f).start)
        val zoomed=chartWindow(241,151,90,zoom=2f,anchor=1f)
        assertEquals(241,zoomed.start+zoomed.span)
        assertFailsWith<IllegalArgumentException> { chartWindow(0,0,10) }
    }
    @Test fun thsIdentitiesAreDistinctFromEastmoneyAndUnqualifiedNewsIsRejected() {
        assertEquals("CN.concept.THS_308642",checkedAssetId("CN.concept.THS_308642"))
        assertFailsWith<IllegalArgumentException> { checkedAssetId("CN.concept.THS_308642/../../token") }
        val data=Json.parseToJsonElement("""{"items":[{"region":"US","authority_level":"company","original_url":"https://nvidianews.nvidia.com.evil.test/news/x","source":"official/nvidia/priority-news/v1","time_precision":"date"}]}""").jsonObject
        assertFailsWith<IllegalArgumentException> { parsePriorityNews(data) }
    }
}

@OptIn(ExperimentalTestApi::class)
class SevenDayChartInterfaceTest {
    @Test fun buttonsAndPinchChangeActualWindowAndMinuteUsesDatedSnapshot()=runDesktopComposeUiTest(390,844) {
        val asset=DailyAsset("CN.stock.000001","平安银行","000001",date="2026-10-15")
        val candles=(0..240).map { i -> Candle(LocalDate(2025,10,17).plus(DatePeriod(days=i)).toString(),10.0,11.0,9.0,10.5,null,null,"fixture") }
        val snapshot=IntradaySnapshot("2026-10-15",listOf(SnapshotMinutePoint("2026-10-15T09:30:00+08:00",10.0),SnapshotMinutePoint("2026-10-15T15:00:00+08:00",10.5)),"current")
        val model=RevisedViewModel(initial=revisionFixture().copy(detail=DailyDetail(asset,candles,loading=false,complete=true,historyWeeks=52,intraday=snapshot)))
        try {
            setContent { App(model) }
            onNodeWithTag("chart-52w").performClick()
            onNodeWithTag("chart-range").assertTextContains("241 日",substring=true)
            onNodeWithTag("chart-zoom-in").performClick()
            onNodeWithTag("chart-range").assertTextContains("172 日",substring=true)
            val before=onNodeWithTag("chart-range").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            onNodeWithTag("daily-candles").performTouchInput { pinch(center-Offset(35f,0f),center+Offset(35f,0f),center-Offset(120f,0f),center+Offset(120f,0f),300) }
            val after=onNodeWithTag("chart-range").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
            assertNotEquals(before,after,"Pinch must change the visible data window")
            onNodeWithTag("chart-minute").performClick()
            onNodeWithTag("daily-intraday").assertExists()
            onNodeWithTag("chart-range").assertTextContains("2026-10-15",substring=true)
            onNodeWithText("腾讯行情 · 已与当日日线收盘核对；收盘快照，不是实时行情。").assertExists()
        } finally { model.close() }
    }
}
