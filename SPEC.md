# DidiVoiceBlocker 项目开发规格说明书

> 更新版本：2026-07-07
> 变更说明：音频检测方案从页面嗅探改为音频流检测，实现精确时长记录和三种放行条件

---

## 1. 项目结构

```
DidiVoiceBlocker/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/didi/voiceblocker/
│   │   ├── MainActivity.kt              - 主界面(白名单管理 + 记录 + 统计)
│   │   ├── SmartVoiceBlocker.kt         - 无障碍服务(关键词检测)
│   │   ├── AudioMonitorService.kt        - 音频监控服务(核心: 音频流检测+时长)
│   │   ├── FloatingBallService.kt         - 悬浮球服务
│   │   ├── BootReceiver.kt               - 开机自启
│   │   └── ConfigManager.kt              - SharedPreferences 封装
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml          - 主界面布局
│       │   └── floating_ball.xml         - 悬浮球布局
│       └── values/strings.xml
```

---

## 2. AndroidManifest.xml

**需要的权限：**
- `BIND_ACCESSIBILITY_SERVICE` — 无障碍
- `SYSTEM_ALERT_WINDOW` — 悬浮窗
- `FOREGROUND_SERVICE` — 保活
- `FOREGROUND_SERVICE_SPECIAL_USE` — Android 14+ 需要
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — 音频播放监控
- `RECEIVE_BOOT_COMPLETED` — 开机自启
- `POST_NOTIFICATIONS` — 通知
- `QUERY_ALL_PACKAGES` — 检测滴滴是否运行

**声明的组件：**
- `MainActivity` — LAUNCHER
- `SmartVoiceBlocker` — 无障碍服务
- `AudioMonitorService` — 音频监控前台服务
- `FloatingBallService` — 前台服务, type=specialUse
- `BootReceiver` — 开机广播

---

## 3. ConfigManager.kt

封装 `SharedPreferences("blocker_config")`：

**字段：**
- `enabled: Boolean` — 总开关
- `allowTexts: MutableSet<String>` — 放行关键词（预约单/实时单相关）
- `allowResourceIds: MutableSet<String>` — 放行资源 ID
- `blockHints: MutableSet<String>` — 强制静音关键词（默认安全提醒类，已废弃）
- `muteStats: String` — 统计 JSON

**方法：**
- `save()` / `reload()`
- `addText(text)` / `removeText(text)`
- `addResourceId(id)` / `removeResourceId(id)`
- `recordMute()` / `recordUnmute()` / `getStats()`
- `addPlaybackRecord(record: PlaybackRecord)` — 记录播报事件

---

## 4. AudioMonitorService.kt（核心 — 音频流检测）

### 4.1 音频检测原理

**核心问题**：滴滴的 `AudioPlaybackCallback.playbackState.state` 始终显示 `PLAYING`，即使没有实际音频播报（session 长驻），这会导致误检。

**解决方案：state + position 双重判断**

```
滴滴进程音频播放
    ↓
检测 AudioPlaybackCallback 的 playbackState.state == PLAYING
    ↓
同时检测 playbackState.position 是否在变化
    ↓
state=PLAYING + position 在变 → 真在播（播报进行中）
state=PLAYING + position 不变 → 假阳性（session 存在但无实际音频，忽略）
    ↓
position 连续 N 秒无变化 → 播报结束
```

**判断逻辑表：**

| state | position | 判断结果 |
|-------|----------|---------|
| PLAYING | position 在变化 | 真在播 |
| PLAYING | position 不变 | 假阳性（误检） |
| 非 PLAYING | — | 空闲 |

### 4.2 状态机

```
[空闲]
    ↓ state=PLAYING + position 变化
[播报中]
    ↓ position 连续 3 秒不变
[播报结束] → 记录时长/状态 → [空闲]
    ↓
同时检测通知？
    ↓
通知存在 → [放行]
    ↓
通知不存在 + 关键词不匹配 → [静音]
```

### 4.3 关键方法

**`onAudioPlaybackUpdate(playbackState)`**：
- 接收 `AudioPlaybackCallback` 的 `playbackState`
- 提取 `state` 和 `position` 值
- 判断是否从假阳性状态恢复

**`checkPositionChange()`**：
- 定时检查 `playbackState.position` 是否在变化
- position 增长中 → 播报进行中，继续监听
- position 连续 3 秒不变 → 播报结束，触发决策
- state=PLAYING 但 position 从不变 → 误检，忽略

**`ensureDidiInForeground()`**：
- 当检测到滴滴音频但滴滴不在前台时
- 调用 `moveTaskToFront()` 或启动滴滴 Activity 将其拉到前台
- 等待无障碍服务完成关键词检测

**`muteDidi()`**：
- `audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)`

**`unmuteDidi()`**：
- `audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)`

### 4.4 多通道音频检测

滴滴可能不只使用 `STREAM_MUSIC` 一个通道，需要多通道检测：

| 通道 | 检测方式 | 说明 |
|------|----------|------|
| `STREAM_MUSIC` | `AudioPlaybackCallback` + position 变化 | 主要播报通道 |
| `STREAM_VOICE_CALL` | `AudioPlaybackCallback` | 语音通话（不干扰） |
| `STREAM_ALARM` | `AudioPlaybackCallback` | 闹钟（不干扰） |
| `AudioAttributes.USAGE_MEDIA` | `AudioPlaybackCallback` | 通用媒体音频 |
| `AudioAttributes.USAGE_VOICE_COMMUNICATION` | `AudioPlaybackCallback` | 语音通信 |

任一通道检测到有效音频（state=PLAYING + position 变化）即触发检测。

### 4.5 性能保护

| 机制 | 值 | 说明 |
|------|-----|------|
| position 检测间隔 | 500ms | 定时检查 position 值 |
| 播报结束判定 | 稳定 1 秒 | position 连续 1 秒无变化视为结束 |
| 误检保护 | — | state=PLAYING 但 position 从不变化不触发静音 |
| 最大播报时长 | 30 秒 | 超过此值强制结束播报检测 |

---

## 5. SmartVoiceBlocker.kt（关键词检测）

**触发时机**：滴滴被拉到前台后，由 AudioMonitorService 调用

继承 `AccessibilityService`：

- **`onServiceConnected`**：配置 `eventTypes`, `packageNames=["com.sdu.didi.gsui"]`
- **`onAccessibilityEvent`**：
  - 仅处理 `TYPE_WINDOW_STATE_CHANGED` 和 `TYPE_WINDOW_CONTENT_CHANGED`
  - 仅处理 `com.sdu.didi.gsui` 包
  - 每次读 `ConfigManager`（支持热更新）

### 5.1 放行关键词（三种情况）

**情况一：预约单页面**
- `"预约单"` — 预约单三字本身
- `"专车舒适"` — 专车舒适四字
- `"不抢"` — 不抢二字

**情况二：实时单页面（接乘客阶段）**
- `"实时单"` — 实时单三字
- `"接乘客"` — 接乘客三字

**情况三：音频播报 + 通知同时到来**
- 检测到 Audio Session 建立的瞬间，同时有 Notification 到达
- 视为放行条件

### 5.2 扫描方法

```kotlin
private fun scanWithDetails(node: AccessibilityNodeInfo): ScanResult {
    // 递归遍历所有节点
    // 检查 text / contentDescription / viewIdResourceName
    // 匹配上述三类放行关键词
    // 返回: ALLOW(放行) / MUTE(静音) / UNKNOWN(未知)
}
```

### 5.3 决策逻辑

```
检测到关键词[预约单/专车舒适/不抢] → 放行
检测到关键词[实时单/接乘客] → 放行
同时有 Audio + Notification → 放行
其他情况 → 静音
```

---

## 6. FloatingBallService.kt

前台服务（避免被杀）：

### 6.1 悬浮球样式

- **形状：圆形**（50dp 直径）
- **颜色状态：**
  - 静音：灰色背景 + 静音图标
  - 放行：绿色背景 + 喇叭图标
  - 禁用：红色背景 + X 图标
  - 播报中：蓝色背景 + 波动图标

### 6.2 布局参数

```kotlin
type = TYPE_APPLICATION_OVERLAY
flags = FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS
gravity = TOP | START
format = PixelFormat.TRANSLUCENT
```

### 6.3 触摸处理

- `ACTION_DOWN`：记录起始坐标
- `ACTION_MOVE`：移动悬浮球
- `ACTION_UP`：
  - 移动距离 < 10dp → 视为点击 → 展开菜单
  - 否则 → 拖动，保存新位置

### 6.4 菜单排列方式

**180度半圆环绕**：PopupWindow 的菜单项以悬浮球为圆心，在悬浮球上方呈半圆形排列（180度弧度），从左到右依次排列各菜单项。

**菜单内容：**
- [🔍 当前状态] — 显示静音/放行/播报中
- [📋 白名单管理] → 调起 MainActivity 并传入 "whitelist" intent
- [📊 播报记录] → 调起 MainActivity 并传入 "records" intent
- [⚙️ 总开关] → 切换 `ConfigManager.enabled`
- [❌ 关闭悬浮球] → `stopSelf`

### 6.5 前台通知

- `channelId = "blocker_floating"`
- `importance = LOW`
- 显示"滴滴静音器工作中",带 [关闭] action

### 6.6 状态广播

- 接收 `AudioMonitorService` 的 `ACTION_PLAYBACK_STATE_CHANGED`
- 根据状态切换悬浮球图标

---

## 7. MainActivity.kt

主界面布局（`activity_main.xml`），垂直 `LinearLayout`：

**顶部状态栏：**
- 标题："Didi Voice Blocker v1.0"
- 状态文字："已启用 - 播报中/静音中/放行中/空闲"
- [总开关] Switch

**中间 — Tab 切换：**
- Tab 1：白名单管理
- Tab 2：播报记录
- Tab 3：系统状态

### Tab 1：白名单管理

**列表区域可滚动**（ScrollView 包裹 ListView）

- [预约单关键词] 子区域：
  - 输入框 + [+] 按钮
  - ListView（每行:文本 + [删除] 按钮）
  - 默认：`"预约单"`, `"专车舒适"`, `"不抢"`
- [实时单关键词] 子区域：
  - 输入框 + [+] 按钮
  - ListView
  - 默认：`"实时单"`, `"接乘客"`
- [强制静音关键词] 子区域（黑名单，已废弃）：
  - 输入框 + [+] 按钮
  - ListView

### Tab 2：播报记录（调试窗口改造）

**本次运行的所有播报事件记录**（列表区域可滚动）：

| 字段 | 说明 |
|------|------|
| 序号 | 递增编号 |
| 开始时间 | 播报开始时刻 HH:MM:SS |
| 结束时间 | 播报结束时刻 HH:MM:SS |
| 持续时长 | 毫秒数 |
| 是否静音 | 是/否 |
| 放行原因 | 预约单/实时单/通知同时/无原因 |

- [清空记录] 按钮

### Tab 3：系统状态

- 无障碍服务状态：已开启/未开启
- 音频监控服务状态：运行中/未运行
- 悬浮窗权限：已授权/未授权
- 当前滴滴状态：前台/后台/未运行
- 本次播报统计：总次数、静音次数、放行次数

**底部固定按钮：**
- [启动悬浮球] — 检查权限后启动 `FloatingBallService`
- [关闭悬浮球] — `stopService`

**首次启动引导：**
- 检测权限：无障碍、悬浮窗
- 没授权显示引导卡片，带"去授权"按钮

---

## 8. BootReceiver.kt

**`onReceive`：**
- `Intent.ACTION_BOOT_COMPLETED`：
  1. 检查 `ConfigManager.enabled`
  2. 如果启用：启动 `AudioMonitorService` 和 `FloatingBallService`
  3. `SmartVoiceBlocker` 由系统自动管理

---

## 9. 默认配置

**`allowTexts`（放行关键词 — 情况一/二）：**

```
// 预约单
"预约单", "专车舒适", "不抢"

// 实时单（接乘客阶段）
"实时单", "接乘客"
```

**`allowResourceIds`（放行资源 ID）：**
```
"as_widget_realtime_order_title_text",
"layout_booking_order_list_item",
"order_review_head_tag_view",
"activity_order_confirm",
"activity_order_review",
"fragment_order_review",
"fragment_order_qr",
"travel_detail_order_info_address_from_txt",
"travel_detail_order_info_address_to_txt",
"travel_detail_order_info_passenger_count",
"layout_selfdriving_bottom_order_detail_view"
```

**`blockHints`（强制静音关键词，已废弃）：**
```
"SafetyReminder", "FatigueAlert", "DrivingTime",
"RestReminder", "SafetyDialog", "安全提醒",
"疲劳驾驶", "请注意休息"
```

---

## 10. 播报记录数据结构

```kotlin
data class PlaybackRecord(
    val id: Int,           // 序号
    val startTime: Long,    // 开始时间戳(ms)
    val endTime: Long,      // 结束时间戳(ms)
    val duration: Long,     // 持续时长(ms)
    val wasMuted: Boolean,  // 是否静音
    val allowReason: String // 放行原因: "预约单" / "实时单" / "通知同时" / "无"
)
```

---

## 11. 关键技术要点

1. **音频检测不依赖页面**：无论滴滴在前台还是后台，只要有音频播报就检测得到
2. **state + position 双重确认**：防止误检（滴滴 session 长驻，state 始终 PLAYING，但 position 不变 = 假阳性）
3. **滴滴后台时拉起前台**：检测到后台播报时，自动将滴滴切到前台以便无障碍检测
4. **精确时长记录**：记录每次播报的开始时间、结束时间、持续时长
5. **通知同时性判断**：音频和通知同时发生时，视为放行条件

---

## 12. 编译和输出

1. 跑 `gradle assembleDebug`
2. 如果报错就修
3. 成功后输出：`APK_PATH=/root/DidiVoiceBlocker/app/build/outputs/apk/debug/app-debug.apk`

**【要求】**
- 代码必须能编译通过
- 所有 UI 元素都要中文标签
- 不要 root 方案
- 完成后只输出 APK 路径，不要长篇报告
