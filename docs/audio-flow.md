# 滴滴静音器 — 音频检测与决策流程

## 整体流程图

```
音频事件发生
    ↓
AudioPlaybackCallback.onPlaybackConfigChanged()
    ↓
handlePlaybackConfigs()
    │
    ├─ ① 有任何音频活动（不限usage）
    │     → lastActivityTime = now
    │     → state==IDLE ? startPlayback() : 重置稳定计时
    │
    ├─ ② relevantConfigs 为空（usage非1/2/13）
    │     → checkForSilence()  检查是否该解除静音
    │
    └─ ③ relevantConfigs 不为空
          → 记录日志（调试用）

startPlayback()          ←── 音频活动开始
    ↓
立即 ensureMuted()        ←── 第①时间静音，防止漏音
    ↓
pullDidiToForeground()   ←── 把滴滴拉到前台
    ↓
startService(SmartVoiceBlocker.ACTION_REQUEST_PAGE_CHECK)
    ↓
SmartVoiceBlocker 接收请求
    ↓
等待 pageScanDelayMs（页面切换完成）
    ↓
scanAccessibilityTree()  ←── 遍历节点，匹配白名单/黑名单
    ↓
broadcast(ACTION_CHECK_PAGE_RESULT, allowsAudio)
    ↓
AudioMonitorService.handlePageCheckResult()
    ↓
allowsAudio=true  →  ensureUnmuted()  放行
allowsAudio=false →  keep muted       静音
```

## 6个参数位置说明

| 参数名（中文面板） | 变量名 | 作用阶段 | 默认值 | 当前值 |
|---|---|---|---|---|
| 静音判定阈值 | `silenceThresholdMs` | 沉默检测 | 3000ms | 4000ms |
| 位置检测间隔 | `positionCheckIntervalMs` | 沉默检测（轮询周期） | 500ms | — |
| 最大播报时长 | `maxPlaybackDurationMs` | 沉默检测（强制结束） | 30000ms | — |
| 页面检测超时 | `scanTimeoutMs` | 页面检测 | 5000ms | — |
| 防抖判定时间 | `debounceMs` | 页面检测（未使用） | 200ms | — |
| 页面扫描延迟 | `pageScanDelayMs` | 页面检测（等待加载） | 250ms | — |

## 各参数详解

### 静音判定阈值（silenceThresholdMs）
- **位置**：`positionCheckRunnable` 轮询中、`checkForSilence()`
- **含义**：音频停止后，持续多少秒判定播报真正结束
- 当前 4000ms：从最后一条音频回调起，4秒无活动才触发 unmute
- 调大（5000ms）：减少"播报中间间隙"误判，但 unmute 慢
- 调小（2000ms）：可能提前 unmute，最后几个字漏音

### 位置检测间隔（positionCheckIntervalMs）
- **位置**：`positionCheckRunnable` 的 `handler.postDelayed(this, POSITION_CHECK_INTERVAL_MS)`
- **含义**：多久轮询一次"是否该 unmute"
- 当前 500ms：每 0.5 秒检查一次沉默时长是否达到阈值
- 调小：响应更快（但更耗电）
- 调大：响应慢但省电

### 最大播报时长（maxPlaybackDurationMs）
- **位置**：`checkForSilence()`、`positionCheckRunnable`
- **含义**：强制结束的最大播报时长（安全网）
- 当前 30000ms：超过 30 秒强制 unmute，防止无限静音

### 页面检测超时（scanTimeoutMs）
- **位置**：`SmartVoiceBlocker.scanWithDetails()` 递归时检查
- **含义**：Accessibility 树扫描超时上限
- 当前 5000ms：5 秒内找不到白名单，默认静音（不误放）

### 防抖判定时间（debounceMs）
- **位置**：有定义但**目前未使用**
- 原意：连续页面检测时的去抖间隔

### 页面扫描延迟（pageScanDelayMs）
- **位置**：`SmartVoiceBlocker.checkCurrentPageAndNotify()` 的 `handler.postDelayed`
- **含义**：拉起滴滴后，等待页面加载完成的时间
- 当前 250ms

## 关键设计决策

- **立即静音原则**：`startPlayback()` 立即 mute，不等页面检测结果，防止漏音
- **页面检测决定放行**：检测到预约单/实时单关键词 → unmute；否则保持静音
- **超时默认静音**：5 秒扫不到白名单 → 默认静音（不误放）
- **双保险沉默检测**：轮询 `SILENCE_THRESHOLD` + 最大时长 `MAX_PLAYBACK_DURATION`
