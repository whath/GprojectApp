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
                onNodeWithText("MA60").performClick()
                onNodeWithText("应用并扫描").performClick()
                onNodeWithText("MA60  ·  RSI 40–75  ·  量比 ≥ 1.0").assertExists()
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
