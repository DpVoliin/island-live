# 库 API

包名 `dev.dpvoliin.liveupdates`。公开 API 只有 4 个类型：`LiveUpdate`、`LiveUpdateSpec`、
`CapabilityReport`、`LiveUpdateAlarmReceiver`（后者仅供系统调用）。

## LiveUpdate（门面）

| 方法 | 说明 |
|---|---|
| `init(context)` | App 启动时调一次（幂等）。撤下上次进程被杀时残留的过期条目，给未到期的重排兜底闹钟 |
| `publish(context, id) { … }` | 推一条「进行中活动」。返回 `false` 表示连通知权限都没有，什么都没发生 |
| `update(context, id) { … }` | 原地更新。**保留原超时时间**（反复刷新进度不会把下课时间越推越远）。id 不存在时返回 `false`，需要回落 `publish` |
| `finish(context, id)` | 从岛上撤下。幂等 |
| `finishAll(context)` | 撤下本库发出的全部条目 |
| `isActive(context, id)` / `activeIds(context)` | 查当前活着的条目 |
| `isSupported(context)` | 简版能力判断 |
| `capability(context)` | 完整能力报告，见下 |

Java 友好重载：`publish(context, id, title, text, progressUsed, progressTotal, smallIconRes, timeoutMillis)`。

## LiveUpdateSpec.Builder（DSL）

```kotlin
LiveUpdate.publish(context, "class-1") {
    title = "正在上课 · 高等数学"          // 必填
    text = "还有 26 分钟下课 · 3教305"
    subText = "10:00 – 10:45 · 张建国"
    smallIconRes = R.drawable.ic_class    // 单色小图标；不填则回落应用图标
    largeIcon = null                      // android.graphics.drawable.Icon
    contentIntent = pendingIntent         // 点岛跳转
    timeoutMillis = 50 * 60_000L          // 超时自动撤下；<=0 表示不撤（默认 90 分钟）
    ongoing = true                        // 实时通知要求 true（划不掉）
    channelId = LiveUpdate.DEFAULT_CHANNEL_ID
    channelName = LiveUpdate.DEFAULT_CHANNEL_NAME
    showTimestamp = false
    alertOnce = true

    progress(used = 26, total = 45)       // 确定性进度（比例换算成 0..100）
    progressPercent(58)                   // 或直接给百分比
    segments(50, 50)                      // 分段刻度（长度之和通常为 100），一节课一段
    progressPoints(25, 50, 75)            // 进度点标记
}
```

- `progress()` 不设置时 = 不确定进度（岛通常只显示标题，部分系统不显示进度）。
- 进度上限固定为 100（系统 `ProgressStyle.getProgressMax()` 是常量），所以 `percent` 自动换算。
- `id` **不要包含 `|`**：它被用作落盘记录的分隔符。

## CapabilityReport

```kotlin
val report = LiveUpdate.capability(context)
report.sdkInt                  // API 级别
report.androidRelease          // "16"
report.manufacturer / model
report.isAndroid16OrAbove      // 系统版本是否够（实时通知的底线）
report.canPromote              // ★ 系统是否允许提升为实时通知
report.notificationsEnabled    // 通知总开关 + POST_NOTIFICATIONS 运行时权限
report.liveUpdateChannelBlocked// 本库默认通道是否被用户手动关掉
report.isFullyReady            // 三者全绿
report.headline                // 一句人话结论，可直接显示在引导页
report.details                 // 多行明细，适合"上报机型"时复制
report.oemHint                 // 按厂商给的引导文案（vivo/小米/OPPO/华为/三星），未知厂商为 null
```

## 行为约定（写代码时可以直接依赖）

1. **能力不足不报错**：不支持 Live Updates 的系统上，通知照样发，只是不会变成岛。
2. **没有通知权限时静默失败**：`publish`/`update` 返回 `false`，不抛异常。
3. **同一 id 复用同一条通知**（tag = `liveupdates:<id>`），不会在通知栏堆一串。
4. **进程被杀也能准时撤下**：超时走 `AlarmManager` 兜底广播，不依赖进程存活。
5. **下次启动自动清理**：`init()` 会撤下已过期的残留（否则会留一个划不掉的常驻通知）。
6. **更新节流由调用方决定**：库不做隐藏节流，建议「只在显示值变化时推送，最快 1 秒一次」。
