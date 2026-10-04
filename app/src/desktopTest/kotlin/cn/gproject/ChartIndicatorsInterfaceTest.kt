package cn.gproject

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

@OptIn(ExperimentalTestApi::class)
class ChartIndicatorsInterfaceTest {
    @Test fun dailyIndicatorsShareDateAndHistoryWindowOnPhone() = runDesktopComposeUiTest(390,844) {
        val model=MarketViewModel(initialMonitorSettings=MonitorSettings(),initialWorkspaceSettings=WorkspaceSettings())
        val quote=Quote("demo0","中芯国际","688981")
        val detail=demoStock(quote)
        try {
            model.demo();model.openStock(quote);setContent { App(model) }
            DAILY_MA_PERIODS.forEach { onNodeWithTag("ma-$it").assertExists() }
            onNodeWithTag("macd-chart").performScrollTo().performTouchInput { click(Offset(1f,20f)) }
            val selected=detail.candles.size-30
            onNodeWithTag("candle-date").assertTextEquals(detail.candles[selected].date)
            onNodeWithTag("selected-fund-net").assertTextEquals(money(detail.funds!!.rows.single { it.date==detail.candles[selected].date }.net))
            onNodeWithTag("history-previous").performScrollTo().performClick()
            onNodeWithTag("candle-date").assertTextEquals(detail.candles[detail.candles.size-16].date)
            onNodeWithTag("macd-chart").performScrollTo()
            File("build/previews/phone-chart-indicators.png").apply { parentFile.mkdirs();writeBytes(Image.makeFromBitmap(captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes) }
            onNodeWithTag("toggle-macd").performScrollTo().performClick()
            onNodeWithTag("macd-chart").assertDoesNotExist()
            onNodeWithTag("toggle-funds").performClick()
            onNodeWithTag("stock-funds-panel").assertDoesNotExist()
        } finally { model.close() }
    }
}
