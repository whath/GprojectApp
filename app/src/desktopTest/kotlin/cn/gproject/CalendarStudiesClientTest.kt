package cn.gproject

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CalendarStudiesClientTest {
    @Test fun watchQuoteDoesNotDependOnRankingAndRequestsActualMarketDates(): Unit=runBlocking {
        val api=MarketRepository(HttpClient(MockEngine { request ->
            assertEquals("/v1/watch-quotes",request.url.encodedPath)
            assertEquals("CN.stock.600519",request.url.parameters["ids"]);assertEquals("2026-10-15",request.url.parameters["cn_date"])
            respond("""{"items":[{"id":"CN.stock.600519","name":"贵州茅台","symbol":"600519","status":"current","requested_date":"2026-10-15","trade_date":"2026-10-15","close":1410.5,"change_1d_pct":1.2,"source":"test","currency":"CNY","price_type":"stored_daily_close","adjustment":"none"}]}""",HttpStatusCode.OK)
        }),requestSpacingMillis=0)
        try { api.base="https://example.test";val rows=api.watchCloses(listOf(DailyAsset("CN.stock.600519","贵州茅台","600519")),DailyOverview(cn="2026-10-15",us="2026-10-15"));assertEquals(1410.5,rows.getValue("CN.stock.600519").asset.close) } finally { api.close() }
    }

    @Test fun economicCalendarRequestsAndEnforcesHighImportance(): Unit=runBlocking {
        val api=MarketRepository(HttpClient(MockEngine { request ->
            assertEquals("3",request.url.parameters["min_importance"])
            respond("""{"window_covered":true,"items":[{"id":"low","importance":1},{"id":"high","importance":4,"source":"wallstreetcn/macro-calendar/v1","published_at":null,"occurs_at":"2026-10-20T12:30:00Z","title":"重要数据","region":"美国","country_code":"US"}]}""",HttpStatusCode.OK)
        }),requestSpacingMillis=0)
        try { api.base="https://example.test";assertEquals(listOf("high"),api.dailyCalendar().items.map { it.id }) } finally { api.close() }
    }

    @Test fun meetingDatePrecisionAndOriginalPublisherAreRequired() {
        val text="""{"items":[{"id":"meeting","title":"重要会议","country_code":"CN","category":"meeting","start_date":"2026-10-26","end_date":"2026-10-29","occurs_at":null,"time_precision":"date","timezone":"Asia/Shanghai","importance":4,"source":"official/cn/meeting-announcements/v1","source_page":"https://www.mofcom.gov.cn/a","authority":"official","schedule_status":"announced","issuer":"中央"}],"sources":[]}"""
        assertNull(parseImportantAgenda(Json.parseToJsonElement(text).jsonObject).items.single().time)
        assertFailsWith<IllegalArgumentException> { parseImportantAgenda(Json.parseToJsonElement(text.replace("www.mofcom.gov.cn","www.mofcom.gov.cn.evil.test")).jsonObject) }
        assertFailsWith<IllegalArgumentException> { parseImportantAgenda(Json.parseToJsonElement(text.replace("\"occurs_at\":null","\"occurs_at\":\"2026-10-26T01:00:00Z\"")).jsonObject) }
    }

    @Test fun staleWatchQuoteCannotMasqueradeAsCurrentOrReportMultiDayChange() {
        val text="""{"items":[{"id":"CN.stock.000001","status":"stale","requested_date":"2026-10-15","trade_date":"2026-10-14","close":10.0,"change_1d_pct":null,"source":"test","currency":"CNY","price_type":"stored_daily_close","adjustment":"none"}]}"""
        assertEquals("stale",parseWatchCloses(Json.parseToJsonElement(text).jsonObject,listOf("CN.stock.000001")).values.single().status)
        assertFailsWith<IllegalArgumentException> { parseWatchCloses(Json.parseToJsonElement(text.replace("\"change_1d_pct\":null","\"change_1d_pct\":1.0")).jsonObject,listOf("CN.stock.000001")) }
    }
}

@OptIn(ExperimentalTestApi::class)
class CalendarStudiesInterfaceTest {
    @Test fun chartPriceVolumeStudiesAndInterpretationsShareSelectedDate()=runDesktopComposeUiTest(430,900) {
        val bars=(0..79).map { i -> val p=10.0+i*.03;Candle(LocalDate(2026,7,1).plus(DatePeriod(days=i)).toString(),p,p+.1,p-.1,p+.05,1000.0+i*10,null,"fixture","share") }
        val asset=DailyAsset("CN.stock.000001","平安银行","000001")
        setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { RevisionChart(DailyDetail(asset,bars,loading=false,complete=true,historyWeeks=52)) } } }
        onNodeWithTag("daily-candles").performTouchInput { click(Offset(.5f,center.y)) }
        val selected=onNodeWithTag("chart-selected-price").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        assertTrue(selected.contains(bars.first().date))
        onNodeWithTag("study-meaning-volume").performScrollTo().assertTextContains(bars.first().date,substring=true)
        onNodeWithTag("study-meaning-macd").performScrollTo().assertTextContains("预热",substring=true)
        onNodeWithTag("study-kdj").performScrollTo().assertExists()
        onNodeWithTag("study-meaning-rsi").performScrollTo().assertTextContains(bars.first().date,substring=true)
        onNodeWithTag("study-boll").performScrollTo().assertExists()
        onNodeWithTag("study-macd").performScrollTo().performTouchInput { click(Offset(width*.7f,center.y)) }
        onNodeWithTag("study-meaning-macd").assertTextContains("零轴",substring=true)
    }

    @Test fun watchOutsideTopFiftyShowsPriceDateAndCurrency()=runDesktopComposeUiTest(390,844) {
        val asset=DailyAsset("CN.stock.600519","贵州茅台","600519",1410.5,1.2,"2026-10-15","fixture",periodSessions=1)
        val model=RevisedViewModel(initial=revisionFixture().copy(watched=listOf(asset),watchQuotes=mapOf(asset.id to WatchClose(asset,"current","CNY"))))
        try { setContent { App(model) };onNodeWithText("自选").performClick();onNodeWithText(decimal(1410.5)).assertExists();onNodeWithTag("watch-date-${asset.id}").assertTextContains("2026-10-15 收盘 · CNY",substring=true) } finally { model.close() }
    }

    @Test fun newsHasImportantMeetingAndReleaseTabsAndHidesLowImportance()=runDesktopComposeUiTest(430,900) {
        val day=Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date.plus(DatePeriod(days=2)).toString()
        val meeting=ImportantEvent("m","已公告的中美重要会议","CN","meeting",day,day,null,"Asia/Shanghai","date","中央机关","https://www.gov.cn/a",true,false)
        val release=ImportantEvent("r","重要数据公布日期","US","release",day,day,day+"T12:30:00Z","America/New_York","minute","BEA","https://www.bea.gov/news/schedule",true,false)
        val fixture=revisionFixture();val low=fixture.calendar.single().copy(id="low",title="低重要性事件",importance=1)
        val model=RevisedViewModel(initial=fixture.copy(agenda=ImportantAgenda(listOf(meeting,release)),calendar=fixture.calendar+low))
        try {
            setContent { App(model) };onNodeWithText("资讯").performClick();onNodeWithText("低重要性事件").assertDoesNotExist()
            onNodeWithTag("news-meetings").performClick();onNodeWithText(meeting.title).assertExists()
            onNodeWithTag("news-releases").performClick();onNodeWithText(release.title).assertExists()
        } finally { model.close() }
    }
}
