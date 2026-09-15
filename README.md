# island-live · 岛课表

**把「进行中」的事情推成系统实时通知的开源库 —— 附一个课表 App 作为第一个用法。**
单文件零依赖、全部数据留在本机、只用系统标准 API。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![minSdk 26](https://img.shields.io/badge/minSdk-26-green.svg)](https://developer.android.com/about/versions)
[![targetSdk 36](https://img.shields.io/badge/targetSdk-36-green.svg)](https://developer.android.com/about/versions/16)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-orange.svg)](https://kotlinlang.org)
![阶段：测试阶段](https://img.shields.io/badge/%E9%98%B6%E6%AE%B5-%E6%B5%8B%E8%AF%95%E9%98%B6%E6%AE%B5-yellow.svg)

> ### 🧪 测试阶段说明
> 本项目**目前处于测试阶段**，功能和界面仍在调整，接口也可能变动，**不建议直接用于生产**。
> 系统实时通知（默认通知）能否显示，最终取决于各厂商系统的实现与放行策略，**部分机型可能显示不出来**——
> 这种情况下 App 里的**悬浮岛**仍然可用。欢迎按 issue 模板上报你手上机型的实测结果。
> 遇到问题也欢迎提 issue，作者会尽量修，但**不承诺响应时间**。

---

## 这是什么

这是一个**课表 App**：导入课表后，上课时它会把当前这节课主动显示出来 ——
通知栏里一条带进度的**实时通知**（还有多少分钟下课），加上一条划不掉的**常驻通知**随时看当前课/下一节。
系统不支持实时通知时，App 里的**悬浮岛**（自绘悬浮窗）照样把课程显示出来。

底层能力抽成了一个库 **`liveupdates`**：一行代码把任意「进行中活动」推成一条实时通知，
不支持的机型自动降级为普通通知，不报错、不崩溃。用的是 Android 16 的**标准 API**
（Live Updates），**不接入任何厂商私有组件，也不需要向厂商申请权限**；
各厂商系统的放行情况与设置路径见 [docs/oem-notes.md](docs/oem-notes.md)。

`island-live` 由两部分组成：

- **`liveupdates`** —— 核心库。一行代码把任意「进行中活动」推成系统实时通知，不支持的机型自动降级为普通通知，不报错、不崩溃。
- **`sample-timetable`（岛课表）** —— 用这套能力做的课表 App，也是本库的参考实现。

> **术语**（本仓库三个词各有所指，不混用）
> | 词 | 指什么 |
> |---|---|
> | **默认通知** | 系统实时通知（带进度、随内容更新） |
> | **常驻通知** | 一条划不掉的常驻状态通知，随时看当前课 / 下一节 |
> | **悬浮岛** | App 自绘的贴边悬浮窗，给不支持系统实时通知的机型兜底 |

## 特性

**`liveupdates` 库**

- 一行 API：`publish` / `update` / `finish`，进度条、分段、超时自动撤下
- 能力探测：`capability()` 给出「本机能不能显示实时通知」的人话结论与厂商引导文案
- 不支持的机型自动降级为普通通知；进程被杀后能按落盘状态重建
- 只依赖 `androidx.core`，无厂商 SDK、无私有接口

**岛课表 App**

| 能力 | 说明 |
|---|---|
| **默认通知** | 上课时推「正在上课 · 还有 N 分钟下课」，下课自动撤下 |
| **常驻通知** | 划不掉；常显当前课或下一节，内容每分钟自更新 |
| **悬浮岛** | 拖到屏幕边缘吸附、3 秒自动收起、避让手势区；系统不给实时通知时也能看课 |
| 课表导入 | **Excel(.xlsx) / CSV / TXT / JSON / ICS** 全部本机解析（自研，零第三方依赖）；支持粘贴课表 JSON |
| 教务直连 | WebView 自行登录抓取（正方 / 强智），**账号密码不保存、不上传** |
| 周次与节次 | 周次切换、单双周、跨天找下一节；**节次表按导入数据自适应**（一天几节就画几节） |
| 作息时间 | 侧栏「修改上课时间」逐节改；改一次，显示/提醒/日历/静音全部跟着对齐 |
| 上课提醒 | 提前 0/5/10/15 分钟提醒，可设震动、不响铃；开机自动重排 |
| 系统日历 | 一键把整学期写进系统日历（可清除） |
| 上课自动静音 | 上课切「仅闹钟」，下课自动恢复（需一次性授权勿扰访问） |
| 桌面小组件 | 下一节课 + 实时倒计时 |
| 主题 | 浅色 / 深色 / **跟随系统**（默认跟随系统） |
| 数据备份 | 导出 / 还原整份课表 JSON（换机、重装直接用） |

## 快速开始

**用 App**

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :sample-timetable:assembleDebug
adb install -r sample-timetable/build/outputs/apk/debug/*.apk
```

装好后打开 App：主屏是「下一节课 / 本周课表 / 上课提醒」，其余功能都在左上角 **☰ 侧栏**。
想立刻看到效果：侧栏 →「开启通知」，状态栏顶部就会出现那条实时通知。

**用库**

```kotlin
dependencies { implementation(project(":liveupdates")) }   // 或自行发布到 mavenLocal

// 1) 启动时清一次残留（把上次进程被杀时留下的过期条目撤下）
LiveUpdate.init(context)

// 2) 推一条「进行中活动」
LiveUpdate.publish(context, id = "class-gaoshu") {
    title = "正在上课 · 高等数学"
    text = "还有 26 分钟下课 · 3教305"
    smallIconRes = R.drawable.ic_class
    progress(elapsedSeconds, totalSeconds)   // 进度 → 通知里的进度条
    segments(50, 50)                          // 可选：按节次分段
    contentIntent = openClassDetail()         // 点它跳转
    timeoutMillis = 50 * 60_000L              // 超时自动撤下（进程被杀也生效）
}

// 3) 更新进度（内部已节流）
LiveUpdate.update(context, "class-gaoshu") { progress(27, 45) }

// 4) 结束，撤下
LiveUpdate.finish(context, "class-gaoshu")
```

做引导页时会用到能力探测：

```kotlin
val report = LiveUpdate.capability(context)
report.isFullyReady   // 本机能显示实时通知 + 通知开着 + 通道没被关
report.headline       // 一句人话结论，可直接显示
report.oemHint        // 按厂商给的引导文案
```

完整 API：[docs/api.md](docs/api.md)。

## 支持的系统

显示实时通知需要 **Android 16（API 36）** 及以上的系统底层（vivo OriginOS 6 / 小米 HyperOS 3 / OPPO ColorOS 16 等）。

**老系统照常可用** —— 库会自动降级为普通通知，App 里的**悬浮岛**也能把课程显示出来，
所以低版本机型不是"不能用"，只是那条实时通知会退化成普通通知。各机型实测结果见
[docs/oem-notes.md](docs/oem-notes.md)（欢迎按 issue 模板上报你手上的机器）。

## 仓库结构

```
island-live/
├─ liveupdates/            核心库（纯 Android Library，零 UI 依赖，只依赖 androidx.core）
├─ sample-timetable/       岛课表 App（Kotlin + 自绘 View）
│   ├─ DemoTimetable.kt        节次 / 课程模型与当前课表
│   ├─ TimetableGridView.kt    自绘周课表（"现在"时间线 + 课堂进度）
│   ├─ StickyNotification.kt   常驻通知
│   ├─ NotifyText.kt           通知文案（默认通知 / 常驻通知共用，保证一致）
│   └─ IslandOverlay.kt        悬浮岛
├─ docs/                   API、设计取舍、教务直连、后台存活、机型实测
├─ tools/                  静态自检脚本 · 国内镜像 init 脚本 · 导入模板
└─ .github/workflows/      CI：构建 debug APK 产物（Artifacts 里可直接下载）
```

## 构建

需要 JDK 17+ 与 Android SDK（platform 36 / build-tools 36.0.0）。仓库自带 Gradle wrapper，无需预装 Gradle。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :sample-timetable:assembleDebug
```

不方便本地搭环境的话，直接去 **Actions → `build`** 下载构建好的 debug APK。

> 国内网络：依赖仓库可换阿里云镜像（不影响仓库构建脚本）
> `mkdir -p ~/.gradle && cp tools/gradle-init-cn.gradle ~/.gradle/init.gradle`

## 隐私

- 课表、作息、设置**全部只存在本机**（App 私有目录），不上传、不联网同步、无广告
- 唯一会联网的功能是「**教务直连**」：只在你主动打开时访问你填写的教务网址，且**不保存账号密码**（[docs/jwxt.md](docs/jwxt.md)）
- 崩溃日志只写本机私有目录，用于你主动复制反馈

## 设计取舍

- **只用系统标准 API**：厂商专属组件不对第三方开放 SDK；标准 Live Updates 才是跨厂商、能长期维护的路（[docs/design.md](docs/design.md)）
- **导入必须有校对**：识别一定会有错，所以导入流程让你确认后再入库，而不是"一键完美导入"
- **通知必须能自己撤下**：超时用 `AlarmManager` 兜底，即使进程被杀也会撤；下次启动还会清理残留（[docs/keepalive.md](docs/keepalive.md)）

## 参与

最需要的是**机型实测数据**：装上 App 看实时通知有没有出现 → 按
[.github/ISSUE_TEMPLATE/oem-report.md](.github/ISSUE_TEMPLATE/oem-report.md) 报一条。
一台机器只能测一个机型，而这项能力最终取决于各厂商系统的实现。

## License

[MIT](LICENSE) · 运行时只依赖 AndroidX 与 Material（均 Apache-2.0），无第三方代码捆绑
