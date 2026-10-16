package cn.gproject

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal fun revisionFixture(): DailyUiState {
    val stock=DailyAsset("CN.stock.000001","平安银行","000001",11.12,3.27,"2026-10-15","fixture",1)
    val board=DailyAsset("CN.industry.BK1036","半导体","BK1036",3210.8,5.12,"2026-10-15","fixture",1)
    return DailyUiState(connected=true,preview=true,
        overview=DailyOverview("2026-10-15","2026-10-15","2026-10-15","2026-10-15","2026-10-16",stockCount=96),
        rankings=mapOf("stock" to DailyRanking(listOf(stock),stock.date,96,5576),"industry" to DailyRanking(listOf(board),board.date,90,90)),
        funds=mapOf("industry" to listOf(DailyFund(board.id,board.name,1260000000.0,board.date))),
        indices=listOf(Quote("CN.index.sh000001","上证指数","指数点位",3950.2,.82,"2026-10-15","fixture"),Quote("CN.index.sz399001","深证成指","指数点位",12805.4,1.13,"2026-10-15","fixture")),
        macro=listOf(MacroQuote("gold","黄金美元参考价",4170.58,"USD/troy_ounce",.84,"percent","2026-10-15",List(30){4000.0+it*4+it%3*9},false,1),
            MacroQuote("brent","布伦特现货",126.55,"USD/barrel",.61,"percent","2026-10-14",List(30){120.0+it*.2+it%3},false,2),
            MacroQuote("us2y","2年期美债收益率",4.77,"percent",2.0,"basis_points","2026-10-15",List(30){4.5+it*.01},false,1),
            MacroQuote("us10y","10年期美债收益率",5.10,"percent",3.0,"basis_points","2026-10-15",List(30){4.9+it*.01},false,1)),
        calendar=listOf(CalendarEntry("wscn:1","美国9月消费者价格指数","2026-10-16T12:30:00+00:00","美国","US",3,"%",null,"3.1","3.0",null)),
        policy=listOf(PolicyEntry("pbc:1","中国人民银行调整完善若干货币政策工具","中国人民银行","2026-10-15","https://www.pbc.gov.cn/goutongjiaoliu/113456/113469/2026101510063698366/index.html","official_news")))
}

@OptIn(ExperimentalTestApi::class)
class RevisionInterfaceTest {
    @Test fun phoneNavigationHasOnlyWorkingModulesAndDistinctYieldUnits() = runDesktopComposeUiTest(390,844) {
        val model=RevisedViewModel(initial=revisionFixture())
        try {
            setContent { App(model) }
            onNodeWithText("收盘复盘").assertExists()
            onNodeWithText("信号雷达").assertDoesNotExist();onNodeWithText("连板梯队").assertDoesNotExist();onNodeWithText("AI 分析").assertDoesNotExist()
            save("revision-phone-market")
            onNodeWithText("宏观").performClick()
            onNodeWithText("黄金美元参考价").assertExists()
            save("revision-phone-macro")
            onNodeWithText("资讯").performClick()
            onNodeWithText("美国9月消费者价格指数").assertExists()
            onNodeWithText("政策新闻").performClick()
            onNodeWithText("中国人民银行调整完善若干货币政策工具").assertExists()
            save("revision-phone-policy")
        } finally { model.close() }
    }

    @Test fun unavailableModulesDoNotBecomeEmptyNavigationDestinations() = runDesktopComposeUiTest(390,844) {
        val model=RevisedViewModel(initial=DailyUiState(connected=true,preview=true))
        try {
            setContent { App(model) }
            onNodeWithText("宏观").assertDoesNotExist();onNodeWithText("板块").assertDoesNotExist();onNodeWithText("资讯").assertDoesNotExist()
            onNodeWithText("自选").assertExists();onNodeWithText("待接入").assertDoesNotExist()
        } finally { model.close() }
    }

    @Test fun desktopLayoutAndWatchStateUseRevisedEntryPoint() = runDesktopComposeUiTest(1240,900) {
        val snapshot=revisionFixture()
        val model=RevisedViewModel(initial=snapshot)
        try {
            setContent { App(model) }
            save("revision-desktop-market")
            onNodeWithText("宏观").performClick()
            onNodeWithText("+3.0 bp").assertExists()
            save("revision-desktop-macro")
            runOnIdle { model.toggleWatch(snapshot.rankings.getValue("stock").items.single()) }
            onNodeWithText("自选").performClick()
            onNodeWithText("平安银行").assertExists()
            save("revision-desktop-watch")
            onNodeWithTag("daily-remove-CN.stock.000001").performClick()
            runOnIdle { assertTrue(model.ui.value.watched.isEmpty()) }
        } finally { model.close() }
    }

    @Test fun phoneDetailUsesThirtyCandlesWithoutLongHistoryControls() = runDesktopComposeUiTest(390,844) {
        val fixture=revisionFixture();val asset=fixture.rankings.getValue("stock").items.single()
        val candles=(1..30).map { i -> Candle("2026-09-${i.toString().padStart(2,'0')}",10+i*.01,10.2+i*.01,9.8+i*.01,10.05+i*.01,1000.0,null,"fixture") }
        val model=RevisedViewModel(initial=fixture.copy(detail=DailyDetail(asset,candles,loading=false,complete=true)))
        try {
            setContent { App(model) }
            onNodeWithTag("daily-candles").assertExists()
            onNodeWithText("月K").assertDoesNotExist();onNodeWithText("MA60").assertDoesNotExist();onNodeWithText("全历史").assertDoesNotExist()
            onNodeWithTag("daily-watch").performClick()
            runOnIdle { assertEquals(asset.id,model.ui.value.watched.single().id) }
            save("revision-phone-detail")
        } finally { model.close() }
    }

    private fun ComposeUiTest.save(name: String) {
        val directory=File("build/previews").also { it.mkdirs() }
        File(directory,"$name.png").writeBytes(Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
