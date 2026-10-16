# 观市 · GprojectApp 0.6.1

Kotlin Compose Multiplatform 的 Android/Windows 个人收盘观察应用，依据后端当前真实数据结构重构。后端：[GprojectBack](https://github.com/whath/GprojectBack)，正式地址 `https://ericcself.top`，需要支持 v0.6.x 的服务和个人 Bearer Token。

2026-10-16：52 周 A 股行情已完成真实服务联调，88 项测试通过、0 失败、0 跳过；APK 和 Windows 便携包构建通过。手机可切换近 30 日、近 90 日和 52 周图表。详见 [52 周联调记录](docs/HISTORY_52W_20261016.md)。本轮未新增 Android 真机安装验证，未构建 MSI。

## 当前页面

1. 市场：已有市场指数、A 股/ETF 近 5 日涨幅、11 类美股行业 ETF 代理、AI 配置观察、龙虎榜和席位。
2. 板块：东方财富行业/概念的 5 日榜与主力净额。点击进入 30 日日线、资金和完整当前成分，只有已有行情的成分可以继续打开详情。
3. 宏观：黄金美元参考、布油现货、美债 2/10/30 年期平价收益率，最近 30 个来源观测。收益率变动显示 bp，与商品价格百分比区分。
4. 资讯：未来 7 天经济日历与 5 个官方来源政策新闻；保留公布/预期/前值/修订前值，政策可打开原文。
5. 自选：从现有涨幅榜/详情加入，名称代码本地保存，也可搜索已有日线的 A 股。

手机使用底部导航，900dp 以上使用侧栏；无可用数据的模块不会生成空导航页。A 股详情按服务能力读取近 52 周，其他类别保留原 30 日窗口；缺日明确提示，不补造数据。已去掉连板、信号雷达、模型分析/配置、无界历史扩容和空接口占位。生产入口不提供演示切换；界面测试样本只在测试代码中注入并标识。

## 连接与数据口径

首次打开点击“连接我的数据服务”，输入 HTTPS 地址和已有访问令牌。令牌只在会话内保存，不写磁盘、Git 或产物；真实请求失败会提示并保留可用数据，不替换为模拟行情。手动刷新触发查询，不触发后台采集，没有自动轮询或推送。

后台当前 A 股/美股行情截至 2026-10-15，成分观测日为 2026-10-16。A 股目录已扩至 5577 只，适用的近 52 周行情窗口全部核验，实际有历史行情 5576 只、1320797 条；5 日榜 5554 只，差额为停牌18、上市不足6日4、目标日未上市1，真实数据缺口0。ETF仍2/1702、行业77/496、概念2/508。详见 [52 周联调](docs/HISTORY_52W_20261016.md)。

股票/ETF 的 5 日收益用一次同源前复权快照计算，报价和图表是实际不复权收盘。板块是东方财富独立 BK 编码，不能与 THS 同名分类混接；成分是当前观测，非历史关系。主力净额是来源定义的大单+超大单，不是市场全部现金流。

宏观有各自发布日期与延迟：黄金是新浪 XAU CFD 美元参考，非 LBMA 定盘或期货主连；布油为 EIA 现货；美债为美国财政部平价收益率。政策是官方当前列表，不保证历史文章穷尽。日历北京时间是计划公布时刻，事件可能修订。后端自动采集、补采、巡检和电脑自动备份继续关闭，新日期需要显式手工采集。

## 构建

需要 JDK 17、Android SDK 35。在 Android Studio 打开项目，使用 `local.properties` 的 sdk.dir 或 ANDROID_HOME 指定 SDK。

```powershell
./gradlew.bat :app:run
./gradlew.bat :app:desktopTest
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:createDistributable
```

APK：`app/build/outputs/apk/debug/app-debug.apk`（开发签名）。Windows：`app/build/compose/binaries/main/app/Gproject/Gproject.exe`，须携带整个同目录 app/runtime。业务与 Android 版本 0.6.1，桌面原生包版本 1.6.1。

真实服务测试只有明确提供 `GPROJECT_API_BASE`、`GPROJECT_API_TOKEN` 才运行；本轮已设置并实测全部通过。不要把令牌写入源码或命令日志。GitHub Actions 的无凭证 CI 会跳过真实服务测试并运行其余回归和构建。

当前 A 股年度窗口见 [0.6.1 契约](https://github.com/whath/GprojectBack/blob/main/docs/HISTORY-52W-v0.6.1.md)，其余字段沿用 0.6.0 契约。旧的 BACKEND_REQUIREMENTS、技术信号、长历史和模型文档仅为早期设计存档。
