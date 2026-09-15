# 各机型实测表

本项目**只使用系统标准实时通知**（本仓库称「默认通知」），不接入厂商私有组件、不申请厂商白名单；
下表记录的是各厂商系统对这类通知的**放行情况与设置路径**，供排障参考。

> 这门库的能力**完全取决于 OEM 对 Live Updates 的实现**。同一份代码在不同机器上结果不同，
> 所以下面这张表只能靠实测填。**欢迎按下方模板提 Issue 上报你的机型** —— 一条数据就是一份贡献。

## 怎么测（30 秒）

1. 装 `sample-timetable`（Actions 里的 debug APK，或自行构建）
2. 允许通知权限
3. 点「完整课时 · 45 分钟」
4. 回到桌面 / 锁屏，看通知栏里有没有出现一条**带进度的实时通知**（"还有 N 分钟下课"）
5. 点「下课 · 从岛上撤下」，看它是否立刻消失

## 结果表

| 机型 | 系统 | Android | 实时通知 | 锁屏 | 备注 |
|---|---|---|---|---|---|
| vivo V2458A | OriginOS（vivo，Android 16） | 16 / API 36 | ❌ 不显示 | ❌ | `canPostPromotedNotifications()=false`（系统未放行本应用）；而 App 侧已确认设上：`requestPromotedOngoing=true`、`shortCriticalText=2分`、`ongoing=true`、ProgressStyle 的 extras 齐全 → **通知正常出现，但不能提升为岛**。待验证：**横幅/浮现通知开关**（实测该应用 `isShowHeadsUp=false`）；已排除：appops 无闸门、私有字段被剥离、标准 API 不放行 |
| 待填 | | | | | |

实时通知 / 锁屏列填：✅ 正常 · ⚠️ 有但显示不全 · ❌ 不显示 · ➖ 系统版本不够

> 上报时请附上 App 内「诊断：读回已发出的通知」的截图 —— 那里面有 `canPostPromotedNotifications()` 的结果和通知 extras 的实际内容，
> 能直接区分「App 没设」还是「系统不放行」，比只看现象有用得多。

## 已知前提

| OEM | 需要的最低系统 |
|---|---|
| vivo / iQOO（原子岛） | OriginOS 6（Android 16 底层）及以上 |
| 小米 / Redmi（超级岛） | HyperOS 3（Android 16 底层）及以上 |
| OPPO / 一加 / realme（灵动岛） | ColorOS 16（Android 16 底层）及以上 |
| 华为 / 荣耀（实况窗） | HarmonyOS 6 / MagicOS 10（Android 16 底层）及以上 |
| 三星（实时通知） | One UI 8（Android 16）及以上 |
| Pixel / 原生 | Android 16 及以上 |

> 注意：**只看 Android 版本号是不够的**。「OriginOS 5 = Android 15」这类组合即使升级了底层，
> 也未必支持提升通知。以实测为准。

## vivo 为什么标准 API 不通（2026-09-14 实测 + 官方文档核对）

官方《原子通知接入指导》（`dev.vivo.com.cn/documentCenter/doc/894`）明确要求：

1. 应用**已在 vivo 应用商店上架**；
2. **发邮件申请接入权限**，等 vivo 原子通知团队**审核准入（7-10 个工作日）**；
3. 需求定义 + UI 设计**双方评审定稿**；
4. vivo **开通权限**后才能开始开发、联调；
5. 上线后**机型逐步放量**。

官方《原子通知技术规范》进一步说明：vivo 的原子通知**不是** Android 16 的 Live Updates，
而是自家一套 —— 接 **VPush SDK** 取设备 token，然后在通知 extras 里塞私有字段：

```
notification.superx.operation / template / baseInfos / infos / shortInfos /
capsule / island / clickResp / scene / keepDuration
```

其中 `scene` 是**固定枚举**：`MOVIE / HEALTH_REGISTER / TAXI / TAKEOUT / DELIEVERY /
NAVIGATION / CAR_STATE / METTING / TRAIN / FLIGHT` —— **没有"上课/课表"这一类**。

**结论**：标准 API 在 vivo 上不会被提升（实测 `canPostPromotedNotifications() = false`），
第三方要上原子岛只能走上述合作流程。本项目作为个人开源项目**不申请**，
vivo 侧按「普通常驻通知 + 桌面小组件」交付。

### 铁证：vivo 的岛**是活的**，只是不给我们（2026-09-14 实测）

同一台 vivo V2458A 上，状态栏中间**出现了别的 App 的实时通知**（黄色药丸，显示「已接单」+ 时间），
而本应用的「提升为实时通知」始终是 `false`。同时本应用那条通知的 extras 读回来是齐的：

```
tag=liveupdates:mon-gs  id=1901   常驻(ongoing)=true
android.requestPromotedOngoing = true
android.shortCriticalText = 2分
android.template = android.app.Notification$ProgressStyle
styledByProgress=true  progressPoints=[]  progressSegments=[2 段]
（共 20 项 extras，含 progressMax / progressSegments / styledByProgress / shortCriticalText）
```

即：**App 侧该设的全部设上了；系统侧只对白名单应用（合作方）放行。**
`canPostPromotedNotifications()` 正是那个白名单开关的公开读数 —— 它是 `false` 就说明这条路对个人开发者关着。

### 私有字段这条路也被堵死（「试 V」实测）

按 vivo 官方《原子通知技术规范》的字段名（`notification.superx.operation / template / capsule / island /
baseInfos / infos / shortInfos / clickResp / scene / keepDuration`）**不申请准入、直接塞进 extras** 试了一次，结果：

- 读回已发出的通知：**extras 里连一个 `notification.superx.*` 都没有** —— 被系统清洗掉了
- 「提升为实时通知」仍是 `false`

结论：vivo 对**外来的私有字段做了剥离**（防伪造），这条「绕过准入」的路也走不通。
**vivo = 标准 API（白名单）+ 私有字段（剥离）双封。** 第三方要么走官方准入，要么用下面第四节的悬浮岛方案。

> 实验代码已在 v0.1.13 移除（按钮一并删掉），结论与字段规范永久留档在本节 —— 后来者若要在其它 ROM 上复测，
> 按下面的字段清单往 extras 里塞即可（十几行代码）。

## 2026-09-15 补充实测：appops 这条路也排除掉（指令级证据）

用「一次性全量检测 / 厂商探测」（v0.9.5 起，侧栏 → 实验与排障）在 **vivo V2458A** 上跑只读命令，结果：
> 注：v0.12 起这些测试按钮与面板已从 App 整体移除，本节结论留档（复现时可参考命令本身）。

- `cmd appops get dev.dpvoliin.islandtimetable` → **只回了一条 `SYSTEM_ALERT_WINDOW: default`**，
  没有任何 `POST_PROMOTED*` / island / capsule 相关的 op。
  → **appops 不是 vivo 的闸门**：靠 Shizuku「改应用操作开关」绕过 vivo 这条思路可以正式排除。
- `dumpsys notification` 里出现的 `Island`、`fake_superx_channel`、`easyshare superx` 三个通道，
  属于 **vivo 自家服务**（互传 / 系统 UI），不是第三方能创建的通道。
  → 再次印证：vivo 的原子通知由系统服务按**白名单**驱动，第三方无从介入。
- **两条还没排除的变量**（都在 vivo 的「按应用通知设置」里，属用户可改项）：
  - `dev.dpvoliin.islandtimetable`：`isShowHeadsUp=false`、`isShowKeyguard=false`
  - `dev.dpvoliin.islandtimetable.szk`：`isShowHeadsUp=true`
  → 前者**「横幅 / 浮现通知」是关着的**。这一步需要真机验证：把「横幅通知」和通知优先级打开，
    再点一次「一次性全量检测」，看 ①②③ 哪一号能变成岛。
- `SYSTEM_ALERT_WINDOW: default` → **悬浮岛（侧边悬浮窗）需要用户手动授予「显示在其他应用上层」**，
  vivo 上记得开，否则悬浮岛也浮不出来（这是第四节的兜底方案在 vivo 上的前提）。

**当前结论（vivo）**：标准 API 不放行（`canPostPromotedNotifications()=false`）、私有字段被剥离、
appops 无闸门 → 只剩两条路：① 官方准入（个人项目走不通）② **悬浮岛兜底**（需 SYSTEM_ALERT_WINDOW 权限）。
