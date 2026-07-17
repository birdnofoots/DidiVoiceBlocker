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

## 文档

- [docs/SPEC.md](docs/SPEC.md) — 完整开发规格说明书（各模块、配置、数据结构）
- [docs/audio-flow.md](docs/audio-flow.md) — 音频检测与放行决策流程、可调参数详解

## 免责声明

本项目仅供个人学习与研究使用。使用无障碍服务与音频监控涉及对第三方应用的交互，请在遵守相关应用条款与当地法律法规的前提下自行评估使用风险。
