# 滴滴静音器 · DidiVoiceBlocker

一款面向滴滴车主的 Android 辅助应用：**自动静音滴滴语音播报**，并对预约单/实时单页面智能放行，附带**司机数据面板与高峰时长计时**。

- 应用名：滴滴静音器
- 包名：`com.didi.voiceblocker`
- 版本：v1.0（versionCode 1）
- 语言：Kotlin
- 无需 Root

## 功能特性

### 语音播报智能静音
- 通过 `AudioPlaybackCallback` 检测滴滴进程音频活动，播报开始时**立即静音** `STREAM_MUSIC`，防止漏音。
- **state + position 双重判断**：滴滴音频 session 长驻会使 `state` 始终为 `PLAYING`，仅当 `position` 持续变化才判定为真实播报，避免误检。
- 沉默达到阈值（默认 4s）或超过最大时长（30s）自动解除静音。

### 页面智能放行
- 检测到播报后把滴滴拉到前台，由无障碍服务扫描页面节点。
- 命中白名单关键词即放行（不静音），否则保持静音；扫描超时默认静音（不误放）。
  - 预约单相关：`预约单`、`专车舒适`、`不抢`
  - 实时单相关：`实时单`、`接乘客`
  - 音频与通知同时到达也视为放行条件

### 悬浮球控制
- 圆形悬浮球显示当前状态（静音/放行/禁用/播报中），可拖动。
- 半圆环绕菜单：白名单管理、数据面板、总开关、停止计时、关闭悬浮球。

### 司机数据面板与高峰计时
- 独立悬浮窗显示当日/半月订单数与各高峰时段在线时长。
- 前台计时服务每 60 秒 tick，按工作日/周末的早/晚/夜/午高峰时段累加。
- 半月（1–15 / 16–31）自动清零并导出到 `driver_stats.log`。
- 通过无障碍服务从滴滴页面读取"接单数"。

## 项目结构

```
app/src/main/java/com/didi/voiceblocker/
├── MainActivity.kt                   主界面（白名单/记录/状态）
├── SmartVoiceBlocker.kt              无障碍服务（关键词检测）
├── AudioMonitorService.kt            音频监控服务（核心：音频流检测+时长）
├── FloatingBallService.kt            悬浮球服务
├── BootReceiver.kt                   开机自启
├── ConfigManager.kt                  SharedPreferences 封装
├── PlaybackRecord.kt                 播报记录数据结构
├── DriverDataStore.kt                数据存储（订单+高峰计时）
├── DriverTimerService.kt             前台计时服务
├── DashboardOverlayService.kt        数据面板悬浮窗
└── DashboardAccessibilityService.kt  无障碍服务（读接单数）
```

## 权限说明

| 权限 | 用途 |
|------|------|
| `BIND_ACCESSIBILITY_SERVICE` | 页面关键词检测、读取接单数 |
| `SYSTEM_ALERT_WINDOW` | 悬浮球 / 数据面板 |
| `FOREGROUND_SERVICE` / `*_SPECIAL_USE` / `*_MEDIA_PLAYBACK` | 前台服务保活与音频监控 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `POST_NOTIFICATIONS` | 前台服务通知 |
| `QUERY_ALL_PACKAGES` | 检测滴滴是否运行 |

## 构建

环境：JDK 17、Android SDK Platform 34、AGP 8.7.3、Kotlin 1.9.22（minSdk 26 / targetSdk 34）。

```bash
# 配置 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 构建 Debug APK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 鸿蒙 6.0（HarmonyOS NEXT）适配版

本仓库同时维护一个面向 **HarmonyOS NEXT / 纯血鸿蒙 6.0** 的 ArkTS 工程，位于 `harmonyos/` 目录。

### 当前状态
- Phase 1 MVP 已完成：工程搭建、音频检测、全局媒体静音、无障碍页面扫描放行、主界面与基础设置。
- 后续待实现：悬浮球（`FloatingBallAbility` / `SubWindow`）、数据面板、高峰计时、开机自启替代方案。

### 鸿蒙版与 Android 版的差异
| 维度 | Android 版 | 鸿蒙版 |
|------|------------|--------|
| 开发语言 | Kotlin | ArkTS |
| 应用模型 | Android Service | Stage 模型 + ExtensionAbility |
| 音频检测 | `AudioPlaybackCallback` | `audioManager.on('audioRendererChange')` |
| 静音方式 | `setStreamMute(STREAM_MUSIC)` | `audioManager.setVolume(MEDIA, 0)`（全局媒体音量） |
| 无障碍 | `AccessibilityService` | `AccessibilityExtensionAbility` |
| 悬浮窗 | `WindowManager.addView` | `WindowExtensionAbility` / `SubWindow` |
| 进程内通信 | `LocalBroadcastManager` | `@ohos.events.emitter` |
| 持久化 | `SharedPreferences` | `@ohos.data.preferences` |

### 鸿蒙版关键限制
1. **无 `viewIdResourceName`**：页面扫描只能匹配文本/描述，原 `allowResourceIds` 列表在鸿蒙端基本失效。
2. **静音影响全局**：当前通过把系统媒体音量置 0 实现静音，会同时静音其他媒体 App。如需定向静音，需滴滴接入 `AVSession` 或系统级能力，尚不可行。
3. **无开机自启**：HarmonyOS NEXT 限制公开开机广播，需用户手动启动服务并保持前台通知/悬浮球保活。

### 鸿蒙版构建

环境：DevEco Studio 5.0+ 或 hvigor 命令行，HarmonyOS SDK API 9+（目标 6.0.0/20）。

```bash
cd harmonyos
# 安装依赖
ohpm install
# 构建 HAP
hvigorw assembleHap
# 产物：entry/build/default/outputs/default/entry-default-signed.hap
```

### 鸿蒙版运行前准备
1. 安装 HAP 后，前往 **设置 > 辅助功能 > 无障碍 > 滴滴静音器**，开启无障碍服务。
2. 前往 **设置 > 应用 > 滴滴静音器 > 权限管理**，授予悬浮窗、后台运行、通知等权限。
3. 打开应用，点击"启动音频监控服务"。

### 鸿蒙版项目结构

```
harmonyos/entry/src/main/ets/
├── entryability/MainEntry.ets
├── pages/
│   ├── Index.ets
│   ├── WhitelistPage.ets
│   ├── RecordsPage.ets
│   └── StatusPage.ets
├── services/
│   ├── AudioMonitorService.ets         # 音频检测 + 静音控制
│   ├── SmartVoiceBlockerAbility.ets    # 无障碍页面扫描放行
│   └── DashboardAccessibilityAbility.ets # 读接单数（占位）
├── utils/
│   ├── ConfigStore.ets                 # preferences 配置
│   ├── EventBus.ets                    # emitter 事件总线
│   └── Logger.ets                      # 日志
└── model/PlaybackRecord.ets            # 播报记录模型
```

## 文档

- [docs/SPEC.md](docs/SPEC.md) — 完整开发规格说明书（各模块、配置、数据结构）
- [docs/audio-flow.md](docs/audio-flow.md) — 音频检测与放行决策流程、可调参数详解
- [鸿蒙 6.0 适配计划](.qoder/plans/tough-harbor-drake.md) — 由 Qoder 生成的详细实施计划

## 免责声明

本项目仅供个人学习与研究使用。使用无障碍服务与音频监控涉及对第三方应用的交互，请在遵守相关应用条款与当地法律法规的前提下自行评估使用风险。
