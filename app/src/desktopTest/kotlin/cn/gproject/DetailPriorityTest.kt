package cn.gproject

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import java.io.File
import kotlin.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

@OptIn(ExperimentalTestApi::class)
class DetailPriorityTest {
    @Test fun phoneRetainsDailyRiskReviewWhenChartPeriodChanges() = runDesktopComposeUiTest(390, 844) {
        val model = MarketViewModel(initialMonitorSettings = MonitorSettings(), initialWorkspaceSettings = WorkspaceSettings())
        try {
            model.demo()
            model.openStock(Quote("demo0", "中芯国际", "688981"))
            setContent { App(model) }
            onNodeWithTag("period-WEEK").performClick()
            onNodeWithTag("stock-scroll").performScrollToNode(hasTestTag("detail-setup-TOP_RISK"))
            onNodeWithTag("detail-setup-TOP_RISK").assertExists()
            onNodeWithTag("detail-evidence").performScrollTo().performClick()
            onNodeWithText("收起技术依据").assertExists()
            val bitmap = onRoot().captureToImage().asSkiaBitmap()
            val bytes = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/previews/phone-detail-review.png").apply { parentFile.mkdirs(); writeBytes(bytes) }
        } finally {
            model.close()
        }
    }

    @Test fun focusedChartRetainsErrorRecovery() = runDesktopComposeUiTest(350, 780) {
        var retries = 0
        val detail = StockDetail(Quote("CN.stock.688981", "中芯国际", "688981"), loading = false, error = "行情读取失败")
        setContent { MaterialTheme { StockDetailPage(detail, false, false, {}, { retries++ }) } }
        onNodeWithTag("chart-focus").performClick()
        onNodeWithText("行情读取失败").assertExists()
        onNodeWithText("重试行情").performScrollTo().performClick()
        runOnIdle { assertEquals(1, retries) }
        onNodeWithTag("detail-setup-TOP_RISK").assertDoesNotExist()
    }
}
