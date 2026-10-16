package cn.gproject

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

class RevisedBackendIntegrationTest {
    @Test fun revisedContractAgainstExplicitlyConfiguredBackend(): Unit = runBlocking {
        val base=System.getenv("GPROJECT_API_BASE");val token=System.getenv("GPROJECT_API_TOKEN")
        org.junit.Assume.assumeTrue("Live API credentials not supplied",base!=null && token!=null)
        val api=MarketRepository()
        try {
            api.base=base!!;api.token=token!!
            val overview=api.dailyOverview()
            assertTrue(overview.features.containsAll(setOf("macro","calendar","policy","gainers","board_funds")))
            val ranking=api.dailyRanking("stock",overview.cn)
            assertTrue(ranking.items.size>=5);assertEquals(50,ranking.items.size);assertTrue(ranking.eligible>=50)
            assertEquals(7,ranking.items.first().periodSessions)
            val macro=api.dailyMacro();assertEquals(setOf("gold","brent","us2y","us10y","us30y"),macro.map { it.id }.toSet())
            assertTrue(macro.all { it.trend.size==30 })
            val calendar=api.dailyCalendar();assertTrue(calendar.items.isNotEmpty());assertTrue(calendar.covered)
            assertTrue(api.dailyPolicy().isNotEmpty())
            assertTrue(overview.features.containsAll(setOf("important_calendar","watch_quotes")))
            val agenda=api.importantAgenda()
            assertTrue(agenda.items.any { it.category=="meeting" && it.country=="CN" })
            assertTrue(agenda.items.any { it.category=="meeting" && it.country=="US" })
            assertTrue(agenda.items.any { it.category=="release" && it.country=="CN" })
            assertTrue(agenda.items.any { it.category=="release" && it.country=="US" })
            assertTrue(calendar.items.all { it.importance>=3 })
            val independent=api.watchCloses(listOf(DailyAsset("CN.stock.600519","贵州茅台","600519"),DailyAsset("US.stock.NVDA","NVDA","NVDA")),overview)
            assertTrue(independent.values.all { it.asset.close!=null && it.status=="current" })
            val studies=dailyStudies(api.dailyDetail(DailyAsset("CN.stock.600519","贵州茅台","600519",date=overview.cn),overview).candles)
            assertNotNull(studies.macd.last());assertNotNull(studies.kdj.last());assertNotNull(studies.rsi.last());assertNotNull(studies.boll.last())
            assertEquals(22,api.dailyFocus(overview.boards).size);assertEquals(10,api.dailyRanking("etf",overview.cn).items.size);assertTrue(api.dailyIntraday("CN.stock.000001",overview.cn).points.isNotEmpty())
            val asset=DailyAsset("CN.stock.000001","平安银行","000001",date=overview.cn)
            val detail=api.dailyDetail(asset,overview);assertEquals(52,overview.stockHistoryWeeks);assertEquals(52,detail.historyWeeks);assertEquals(241,detail.candles.size);assertTrue(detail.complete)
            val members=api.dailyMembers("CN.industry.BK1036",overview.members)
            assertTrue(members.size>100);assertEquals(members.size,members.map { it.id }.distinct().size)
        } finally { api.close() }
    }
}

@OptIn(ExperimentalTestApi::class)
class RevisedLiveScreenTest {
    @Test fun desktopRendersRealBackendData() = runDesktopComposeUiTest(1240,900) {
        val base=System.getenv("GPROJECT_API_BASE");val token=System.getenv("GPROJECT_API_TOKEN")
        org.junit.Assume.assumeTrue("Live API credentials not supplied",base!=null && token!=null)
        val model=RevisedViewModel()
        try {
            setContent { App(model) }
            runOnIdle { model.connect(base!!,token!!) }
            waitUntil(timeoutMillis=90000) { !model.ui.value.loading && model.ui.value.macro.size==5 }
            runOnIdle { assertTrue(model.ui.value.errors.isEmpty(),model.ui.value.errors.toString());assertTrue(model.ui.value.policy.isNotEmpty()) }
            save("revision-live-desktop-market")
            onNodeWithText("宏观").performClick();onNodeWithText("黄金美元参考价").assertExists();save("revision-live-desktop-macro")
            onNodeWithText("资讯").performClick();onNodeWithText("政策与资讯").performClick();save("revision-live-desktop-policy")
        } finally { model.close() }
    }

    @Test fun phoneRendersRealBackendAndFiftyTwoWeekDetail() = runDesktopComposeUiTest(390,844) {
        val base=System.getenv("GPROJECT_API_BASE");val token=System.getenv("GPROJECT_API_TOKEN")
        org.junit.Assume.assumeTrue("Live API credentials not supplied",base!=null && token!=null)
        val model=RevisedViewModel()
        try {
            setContent { App(model) }
            runOnIdle { model.connect(base!!,token!!) }
            waitUntil(timeoutMillis=90000) { !model.ui.value.loading && model.ui.value.macro.size==5 }
            runOnIdle { assertTrue(model.ui.value.errors.isEmpty(),model.ui.value.errors.toString()) }
            save("revision-live-phone-market")
            onNodeWithText("宏观").performClick();onNodeWithText("黄金美元参考价").assertExists();save("revision-live-phone-macro")
            runOnIdle { model.open(DailyAsset("CN.stock.000001","平安银行","000001",date=model.ui.value.overview.cn)) }
            waitUntil(timeoutMillis=45000) { model.ui.value.detail?.loading==false }
            runOnIdle { assertEquals(241,model.ui.value.detail!!.candles.size);assertEquals(52,model.ui.value.detail!!.historyWeeks);assertTrue(model.ui.value.detail!!.error.isNullOrBlank()) }
            onNodeWithText("52周").performClick();onNodeWithTag("daily-candles").assertExists();save("revision-live-phone-detail-52w")
        } finally { model.close() }
    }

    private fun ComposeUiTest.save(name: String) {
        val path=File("build/previews/$name.png");path.parentFile.mkdirs()
        path.writeBytes(Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
