package cn.gproject

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import java.io.File
import kotlin.test.Test
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

@OptIn(ExperimentalTestApi::class)
class InterfaceTest {
    @Test
    fun boardSortAndSignalEvidenceRemainReviewableOnPhone() = runDesktopComposeUiTest(390,844) {
        val model=MarketViewModel(initialMonitorSettings=MonitorSettings(),initialWorkspaceSettings=WorkspaceSettings())
        try {
            model.demo();setContent { App(model) }
            onNodeWithTag("page-scroll").performScrollToNode(hasTestTag("boards-decliners"))
            onNodeWithTag("boards-decliners").performClick()
            onNodeWithTag("page-scroll").performScrollToNode(hasTestTag("open-board-demo6"))
            onNodeWithTag("open-board-demo6").performClick()
            runOnIdle { kotlin.test.assertEquals("煤炭",model.ui.value.selected?.name) }
            onNodeWithContentDescription("返回").performClick()
            onNodeWithText("信号雷达").performClick()
            onNodeWithTag("preset-TOP_RISK").performScrollTo().performClick()
            onNodeWithTag("page-scroll").performScrollToNode(hasText("失效条件：",substring=true))
            onNodeWithText("失效条件：",substring=true).assertExists()
            onNodeWithText("查看 ",substring=true).performClick()
            onNodeWithText("收起触发依据").assertExists()
            save("phone-signal-evidence")
        } finally { model.close() }
    }

    @Test
    fun phoneCanAddPinAndRemoveScreenedStocks() = runDesktopComposeUiTest(390,844) {
        val model=MarketViewModel(initialMonitorSettings=MonitorSettings(),initialWorkspaceSettings=WorkspaceSettings())
        try {
            model.demo();setContent { App(model) }
            onNodeWithText("信号雷达").performClick()
            onNodeWithTag("preset-TOP_RISK").performScrollTo().performClick()
            onNodeWithTag("page-scroll").performScrollToNode(hasTestTag("add-pool-demo0"))
            onNodeWithTag("add-pool-demo0").performClick().assertIsNotEnabled()
            onNodeWithText("观察池").performClick()
            onNodeWithTag("page-scroll").performScrollToNode(hasTestTag("pin-demo0"))
            onNodeWithTag("pin-demo0").performClick()
            runOnIdle { model.scan();kotlin.test.assertTrue(model.ui.value.workspace.settings.stocks.single().pinned) }
            onNodeWithText("半导体").assertExists()
            save("phone-watch-pool")
            onNodeWithTag("remove-demo0").performClick()
            runOnIdle { kotlin.test.assertTrue(model.ui.value.workspace.settings.stocks.isEmpty()) }
            onNodeWithText("观察池还是空的").assertExists()
            onNodeWithTag("page-scroll").performScrollToNode(hasTestTag("undo-remove"))
            onNodeWithTag("undo-remove").performClick()
            runOnIdle {
                kotlin.test.assertTrue(model.ui.value.workspace.settings.stocks.single().pinned)
                model.addToPool(Quote("CN.stock.688981","中芯国际","688981"))
                kotlin.test.assertEquals(1,model.ui.value.workspace.settings.stocks.size)
            }
            onNodeWithTag("page-scroll").performScrollToNode(hasTestTag("pool-search"))
            onNodeWithTag("pool-search").performTextInput("不存在")
            onNodeWithTag("page-scroll").performScrollToNode(hasText("没有匹配的观察标的"))
            onNodeWithText("没有匹配的观察标的").assertExists()
        } finally { model.close() }
    }

    @Test
    fun domesticModelConfigurationRemainsInactiveAndUsesSessionOnlyKey() = runDesktopComposeUiTest(1240,1000) {
        val model=MarketViewModel(initialMonitorSettings=MonitorSettings(),initialWorkspaceSettings=WorkspaceSettings())
        try {
            model.demo();setContent { App(model) }
            onNodeWithText("观察池").performClick()
            onNodeWithTag("ai-settings").performClick()
            onNodeWithTag("ai-url").performTextInput("https://provider.example/v1")
            onNodeWithTag("ai-model").performTextInput("deployment-test")
            onNodeWithTag("ai-key").performTextInput("test-session-only")
            save("desktop-ai-settings")
            onNodeWithTag("save-ai").performClick()
            runOnIdle {
                kotlin.test.assertTrue(model.ui.value.workspace.aiKeyPresent)
                kotlin.test.assertFalse(encodeWorkspace(model.ui.value.workspace.settings).contains("test-session-only"))
                model.addToPool(Demo.signals(Rules()).first().quote)
            }
            save("desktop-watch-pool")
            runOnIdle { model.clearAiKey();kotlin.test.assertFalse(model.ui.value.workspace.aiKeyPresent) }
        } finally { model.close() }
    }

    @Test
    fun focusedPhoneChartSupportsInspectionAndHistory() = runDesktopComposeUiTest(390,844) {
        val detail=demoStock(Quote("demo","中芯国际","688981"))
        val model=MarketViewModel(initialMonitorSettings=MonitorSettings())
        try {
        model.demo();model.openStock(detail.quote)
        setContent { App(model) }
        onNodeWithTag("chart-focus").performClick()
        onNodeWithText("常规行情").assertDoesNotExist()
        onNodeWithTag("candlestick-chart").performTouchInput { swipeLeft() }
        onNodeWithTag("history-previous").performScrollTo().performClick()
        onNodeWithTag("history-latest").assertIsEnabled().performClick()
        onNodeWithTag("candle-date").assertTextEquals(detail.candles.last().date)
        onNodeWithTag("history-latest").assertIsNotEnabled()
        onNodeWithTag("toggle-ma").performScrollTo().performClick().assertIsOff()
        onNodeWithContentDescription("放大K线").performClick()
        onNodeWithTag("toggle-ma").performClick().assertIsOn()
        save("phone-chart-focus")
        } finally { model.close() }
    }

    @Test
    fun phoneFlowMonitoringAndStrategyPresets() = runDesktopComposeUiTest(390,844) {
        val model=MarketViewModel(initialMonitorSettings=MonitorSettings())
        try {
            model.demo(); setContent { App(model) }
            onNodeWithTag("open-board-demo1").performScrollTo().performClick()
            onNodeWithTag("flows-20").performScrollTo().performClick()
            onNodeWithTag("watch-board").performClick()
            runOnIdle { kotlin.test.assertTrue(model.ui.value.monitor.settings.watched.none { it.id=="demo1" }) }
            onNodeWithText("多日资金跟踪").performScrollTo()
            save("phone-fund-monitor")
            onNodeWithContentDescription("返回").performClick()
            onNodeWithText("信号雷达").performClick()
            onNodeWithTag("preset-TOP_RISK").performScrollTo().performClick()
            runOnIdle { kotlin.test.assertTrue(model.ui.value.signals.any { s -> s.setups.any { it.preset==StrategyPreset.TOP_RISK } }) }
            onNodeWithText("筛选条件").performScrollTo().performClick()
            onNodeWithText("MA20 / MA125",substring=true).assertExists()
            onNodeWithText("应用并扫描").performClick()
            save("phone-strategy-risk")
        } finally { model.close() }
    }

    @Test
    fun desktopEventReadAndFlowDetails() = runDesktopComposeUiTest(1240,1000) {
        val model=MarketViewModel(initialMonitorSettings=MonitorSettings())
        try {
            model.demo();setContent { App(model) }
            onNodeWithText("全球行情").performClick()
            onNodeWithText("美股 · 重点事件").assertExists()
            onAllNodesWithTag("read-demo-event-0").onFirst().performScrollTo().performClick()
            runOnIdle { kotlin.test.assertTrue(model.ui.value.monitor.settings.readEvents.any { it.startsWith("demo-event-0|") }) }
            save("desktop-market-events")
            onNodeWithText("今日主线").performClick()
            onNodeWithTag("open-board-demo1").performScrollTo().performClick()
            onNodeWithTag("flows-10").performScrollTo().performClick()
            onNodeWithText("查看逐日流入／流出").performScrollTo().performClick()
            save("desktop-fund-monitor")
        } finally { model.close() }
    }

    @Test
    fun desktopBoardAndLeaderDetailNavigation() = runDesktopComposeUiTest(1240, 1000) {
        val model = MarketViewModel()
        try {
            model.demo()
            setContent { App(model) }
            onNodeWithTag("open-board-demo1").performScrollTo().performClick()
            onNodeWithTag("board-switch-left").assertExists()
            onNodeWithTag("board-switch-top").assertDoesNotExist()
            onNodeWithTag("switch-demo2").performClick()
            runOnIdle { kotlin.test.assertEquals("demo2", model.ui.value.selected?.id) }
            save("desktop-board-detail")
            onNodeWithTag("board-scroll").performScrollToNode(hasText("中际旭创"))
            onNodeWithText("中际旭创").performClick()
            onNodeWithTag("stock-detail").assertExists()
            onNodeWithContentDescription("返回").performClick()
            onNodeWithTag("board-detail").assertExists()
            onNodeWithContentDescription("返回").performClick()
            onNodeWithTag("page-scroll").performScrollToNode(hasTestTag("open-leaders"))
            onNodeWithTag("open-leaders").performClick()
            onNodeWithTag("leader-688981").performClick()
            onNodeWithTag("period-WEEK").performClick()
            onNodeWithTag("candlestick-chart").assertExists().performClick()
            save("desktop-stock-week")
            onNodeWithTag("period-MONTH").performClick()
            onNodeWithTag("candlestick-chart").assertExists()
            onNodeWithTag("period-INTRADAY").performClick()
            onNodeWithTag("intraday-chart").assertExists()
            onNodeWithContentDescription("返回").performClick()
            onNodeWithTag("leaders-list").assertExists()
        } finally { model.close() }
    }

    @Test
    fun phoneBoardSwitchAndCandlestickDetail() = runDesktopComposeUiTest(390, 844) {
        val model = MarketViewModel()
        try {
            model.demo()
            setContent { App(model) }
            onNodeWithTag("open-board-demo1").performScrollTo().performClick()
            onNodeWithTag("board-switch-top").assertExists()
            onNodeWithTag("board-switch-left").assertDoesNotExist()
            onNodeWithTag("switch-demo2").performClick()
            save("phone-board-detail")
            onNodeWithTag("board-scroll").performScrollToNode(hasText("中际旭创"))
            onNodeWithText("中际旭创").performClick()
            onNodeWithTag("period-DAY").assertExists()
            onNodeWithTag("candlestick-chart").performClick()
            save("phone-stock-day")
            onNodeWithTag("period-MONTH").performClick()
            onNodeWithTag("candlestick-chart").assertExists()
            onNodeWithTag("period-INTRADAY").performClick()
            onNodeWithTag("intraday-chart").assertExists()
            save("phone-stock-intraday")
            onNodeWithContentDescription("返回").performClick()
            onNodeWithTag("board-detail").assertExists()
        } finally { model.close() }
    }

    @Test
    fun desktopTabsAndFilters() =
        runDesktopComposeUiTest(1240, 1000) {
            val model = MarketViewModel()
            try {
                model.demo()
                setContent { App(model) }
                onNodeWithText("板块轮动").assertExists()
                save("desktop-today")
                onNodeWithText("全球行情").performClick()
                onNodeWithText("美国与大宗商品").assertExists()
                save("desktop-global")
                onNodeWithText("信号雷达").performClick()
                onNodeWithText("筛选条件").performClick()
                onNodeWithText("技术筛选条件").assertExists()
                onNodeWithText("MA125").performScrollTo().performClick()
                onNodeWithText("应用并扫描").performClick()
                onNodeWithText("MA125  ·  RSI 40–75  ·  量比 ≥ 1.0").assertExists()
                save("desktop-signals")
            } finally {
                model.close()
            }
        }

    @Test
    fun phoneNavigationAndEmptyState() =
        runDesktopComposeUiTest(390, 844) {
            val model = MarketViewModel()
            try {
                setContent { App(model) }
                onNodeWithText("等待今日板块数据").assertExists()
                onNodeWithText("体验演示").performClick()
                save("phone-today")
                onNodeWithText("全球行情").performClick()
                save("phone-global")
                onNodeWithText("信号雷达").performClick()
                save("phone-signals")
                onNodeWithText("筛选条件").performClick()
                onNodeWithText("应用并扫描").assertExists()
                save("phone-filters")
            } finally {
                model.close()
            }
        }

    private fun DesktopComposeUiTest.save(name: String) {
        val target = File("build/previews/$name.png")
        target.parentFile.mkdirs()
        target.writeBytes(
            Image.makeFromBitmap(captureToImage().asSkiaBitmap())
                .encodeToData(EncodedImageFormat.PNG)!!
                .bytes
        )
    }
}
