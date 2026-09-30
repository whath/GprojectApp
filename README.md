# 观市 · GprojectApp

Kotlin + Compose Multiplatform + Jetpack ViewModel / MVVM 的 Android、Windows 双端收盘观察应用。

前端独立仓库：https://github.com/whath/GprojectApp 。后端保留在 https://github.com/whath/GprojectBack 。

## 运行

需要 JDK 17、Android SDK 35。Android Studio 打开本目录，配置 `local.properties` 中的 `sdk.dir`，或设置 `ANDROID_HOME`。Windows 执行：

```powershell
./gradlew.bat :app:run
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:desktopTest
./gradlew.bat :app:createDistributable
```

- APK：`app/build/outputs/apk/debug/app-debug.apk`，开发签名，用于内测，不是应用商店发行包。
- Windows：`app/build/compose/binaries/main/app/Gproject/Gproject.exe`。必须保留同目录 app/runtime 文件夹；完整目录可复制部署，无需另装 Java。
- MSI：Windows 执行 `:app:packageMsi`，Compose 插件准备 WiX；输出 `app/build/compose/binaries/main/msi/Gproject-1.0.0.msi`。仅当前用户安装，默认路径 `%LOCALAPPDATA%\Gproject`，未配置发行签名。
- GitHub Actions 每次推送 main 构建 Android APK、Windows 完整目录、MSI 及界面预览并上传构建产物；工作流运行成功后可在 Actions 下载。

## 三个 Tab

1. **今日主线**：行业排行、逐条龙虎榜、连板梯队、重点板块资金与成分表现。点击行业或板块标签切换重点观察。
2. **全球行情**：美股观察池和纽约金主连、布伦特原油；日经、韩国综合；A 股银行、大盘、创业板、科创 50。
3. **信号雷达**：MA5/10/20/60、Wilder RSI14 区间、此前 20 根日线量比、突破此前 20 根日线最高价。组合条件采用 AND，查看结果收盘趋势。

暖白背景、深墨绿文字、低饱和砖红上涨 / 鼠尾草绿下跌。900dp 以上左侧导航与双列内容，手机底部导航。默认空状态，需主动选择演示或连接，模拟数据始终带演示提示。

## 连接与数据边界

设置内填写 HTTPS API 根地址和 Bearer token。令牌只保存在内存，不写入磁盘、日志、构建产物或 Git。HTTP 和自动重定向关闭；服务端需可信证书。服务公网入口未启用时不能在手机上直接连接，客户端不会擅自开启后端公网访问。

**已经对接**：行业排行、龙虎榜、美股配置观察池、板块当日成分快照与日线、A 股已登记标的和日线筛选。日期、来源、缺失状态显示在界面；真实请求失败不降级成演示数据。

**后端尚不支持**：资金流、连板规则与梯队、金油主连、日韩指数及 A 股指数接口。生产模式显示“待接入”，不以成交额充当资金流、不用 ETF 价格充当指数。演示只用于产品与交互验收。完整同花顺成分仍取决于后端供给，缺失时不跨来源替代。

筛选暂以已登记 A 股按 ID 排序的前 30 只为有限观察范围，每只最多 100 根不复权日线；界面显示有效、缺失或失败数量，不宣称全市场扫描。仅最新日期等于服务目标交易日、单一来源、有足够数据的标的参与计算。窗口是实际观测日线，不自动补交易日；没有服务端交易日完整性标志时，缺口会影响指标含义。不复权序列受除权分红影响。本版按用户刷新/扫描更新，没有后台推送。

设置与重点选择在当前会话内有效。未来版本可增加安全凭证存储、持久化策略、多观察池、全市场服务端扫描及后台告警。

## 结构

```text
app/src/commonMain/kotlin/cn/gproject/
  App.kt       Compose 界面与响应式布局
  Market.kt    数据模型、Repository、ViewModel、指标算法、独立演示数据
app/src/androidMain/    Android Activity、OkHttp 引擎
app/src/desktopMain/    桌面窗口、CIO 引擎
app/src/commonTest/     指标边界与数值格式测试
app/src/desktopTest/    接口合约、双尺寸渲染与交互测试
```

View → StateFlow → Jetpack ViewModel → Repository → 现有只读 API。切换连接、演示和筛选时取消旧任务，关闭 ViewModel 清理客户端。

版本选型参考 [Compose 1.8.2 官方说明](https://kotlinlang.org/docs/multiplatform/whats-new-compose-180.html) 与 [Compose 兼容说明](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)。锁定 Kotlin 2.1.20、Compose 1.8.2、AGP 8.7.3、Gradle 8.14，不使用动态版本。

## 界面预览（演示数据）

![桌面今日主线](docs/screenshots/desktop-today.png)

手机界面：[今日主线](docs/screenshots/phone-today.png) · [全球行情](docs/screenshots/phone-global.png) · [信号雷达](docs/screenshots/phone-signals.png) · [技术筛选](docs/screenshots/phone-filters.png)。

详细测试范围与未完成边界见 [验收记录](docs/VALIDATION.md)。
