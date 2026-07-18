import ServiceExtensionAbility from '@ohos.app.ability.ServiceExtensionAbility';
import type Want from '@ohos.app.ability.Want';
import audio from '@ohos.multimedia.audio';
import notificationManager from '@ohos.notificationManager';
import wantAgent from '@ohos.app.ability.wantAgent';
import bundleManager from '@ohos.bundle.bundleManager';
import { BusinessError } from '@ohos.base';
import { ConfigStore } from '../utils/ConfigStore';
import { EventBus, EventId, PlaybackStateEvent } from '../utils/EventBus';
import { Logger } from '../utils/Logger';
import { PlaybackRecord } from '../model/PlaybackRecord';

const TAG = 'AudioMonitorService';
const CHANNEL_ID = 'audio_monitor';
const NOTIFICATION_ID = 1002;
const DIDI_BUNDLE_NAME = 'com.sdu.didi.gsui';

enum PlaybackState {
  IDLE,
  PLAYING
}

export default class AudioMonitorService extends ServiceExtensionAbility {
  private audioManager: audio.AudioManager | null = null;
  private didiUid: number = -1;
  private state: PlaybackState = PlaybackState.IDLE;
  private isMuted: boolean = false;
  private isPlaying: boolean = false;

  private playbackStartTime: number = 0;
  private lastActivityTime: number = 0;
  private positionStableStartTime: number = 0;
  private playbackIdCounter: number = 0;

  private pendingPageCheck: boolean = false;
  private audioTriggerTime: number = 0;
  private originalVolume: number = -1;

  private positionCheckTimer: number = -1;

  private rendererCallback = (info: audio.AudioRendererChangeInfoArray) => {
    this.handleRendererChange(info);
  };

  async onCreate(want: Want): Promise<void> {
    Logger.info(TAG, 'onCreate');
    await ConfigStore.init();
    this.audioManager = audio.getAudioManager();
    await this.resolveDidiUid();
    this.createNotificationChannel();
    this.startForeground();
    this.audioManager?.on('audioRendererChange', this.rendererCallback);
    this.startPositionCheck();
    EventBus.on(EventId.CHECK_PAGE_RESULT, (event) => {
      const data = event.data as Record<string, boolean>;
      this.handlePageCheckResult(data.pageAllowsAudio);
    });
    EventBus.on(EventId.ENABLED_CHANGED, (event) => {
      const data = event.data as Record<string, boolean>;
      if (!data.enabled) {
        this.shutdown();
      }
    });
  }

  onRequest(want: Want, startId: number): void {
    Logger.info(TAG, `onRequest action=${want.action ?? 'null'}`);
    if (want.action === 'STOP') {
      this.shutdown();
    }
  }

  onDestroy(): void {
    Logger.info(TAG, 'onDestroy');
    this.shutdown();
  }

  private async resolveDidiUid(): Promise<void> {
    try {
      const uid = bundleManager.getUidByBundleName(DIDI_BUNDLE_NAME, 0);
      this.didiUid = uid;
      Logger.info(TAG, `Resolved DIDI uid=${uid}`);
    } catch (e) {
      Logger.error(TAG, 'Failed to resolve DIDI uid', e as Error);
      this.didiUid = -1;
    }
  }

  private handleRendererChange(info: audio.AudioRendererChangeInfoArray): void {
    if (!ConfigStore.enabled) {
      if (this.isMuted) this.ensureUnmuted();
      return;
    }

    const allRenderers = Array.isArray(info) ? info : [];
    const didiRenderers = allRenderers.filter(r => r.creatorUid === this.didiUid);
    const runningDidi = didiRenderers.filter(r => r.rendererState === audio.AudioRendererState.RUNNING);

    Logger.info(TAG, `rendererChange total=${allRenderers.length} didi=${didiRenderers.length} running=${runningDidi.length}`);

    if (runningDidi.length > 0) {
      this.lastActivityTime = Date.now();
      if (this.state === PlaybackState.IDLE) {
        this.startPlayback();
      } else {
        this.positionStableStartTime = 0;
        if (this.isMuted) {
          Logger.info(TAG, 're-check page on new audio during mute');
          this.pendingPageCheck = true;
          this.pullDidiToForeground();
        }
      }
    } else if (didiRenderers.length === 0) {
      if (this.state === PlaybackState.PLAYING) {
        this.checkForSilence();
      }
    }
  }

  private startPlayback(): void {
    this.state = PlaybackState.PLAYING;
    this.playbackStartTime = Date.now();
    this.lastActivityTime = Date.now();
    this.positionStableStartTime = 0;
    this.isPlaying = true;
    this.playbackIdCounter++;
    this.audioTriggerTime = Date.now();

    Logger.info(TAG, `>>> START_PLAYBACK #${this.playbackIdCounter}`);
    this.ensureMuted();
    this.pendingPageCheck = true;
    this.pullDidiToForeground();
    this.broadcastState();
  }

  private checkForSilence(): void {
    if (this.state !== PlaybackState.PLAYING) return;
    if (this.positionStableStartTime === 0) {
      this.positionStableStartTime = Date.now();
    }
    const stableDuration = Date.now() - this.positionStableStartTime;
    if (stableDuration >= ConfigStore.silenceThresholdMs ||
      Date.now() - this.playbackStartTime >= ConfigStore.maxPlaybackDurationMs) {
      this.handleSilence();
    }
  }

  private handleSilence(): void {
    if (this.state !== PlaybackState.PLAYING) return;
    const endTime = Date.now();
    const duration = endTime - this.playbackStartTime;
    this.state = PlaybackState.IDLE;
    this.isPlaying = false;
    Logger.info(TAG, `<<< SILENCE duration=${duration}ms isMuted=${this.isMuted} pending=${this.pendingPageCheck}`);

    const record = new PlaybackRecord(
      this.playbackIdCounter,
      this.playbackStartTime,
      endTime,
      duration,
      this.isMuted,
      this.isMuted ? '无' : '待定'
    );
    ConfigStore.addPlaybackRecord(record);

    if (!this.pendingPageCheck) {
      this.ensureUnmuted();
    }
    this.broadcastState();
  }

  private handlePageCheckResult(pageAllowsAudio: boolean): void {
    if (!this.pendingPageCheck) return;
    this.pendingPageCheck = false;
    const elapsed = this.audioTriggerTime > 0 ? Date.now() - this.audioTriggerTime : 0;
    Logger.info(TAG, `=== PAGE_RESULT allowsAudio=${pageAllowsAudio} elapsed=${elapsed}ms ===`);
    this.audioTriggerTime = 0;

    if (pageAllowsAudio) {
      this.ensureUnmuted();
    } else {
      this.ensureMuted();
      if (this.state === PlaybackState.IDLE) {
        this.checkForSilence();
      }
    }
    this.updateLastRecord(pageAllowsAudio);
    this.broadcastState();
  }

  private updateLastRecord(pageAllowsAudio: boolean): void {
    if (ConfigStore.playbackRecords.length === 0) return;
    const last = ConfigStore.playbackRecords[ConfigStore.playbackRecords.length - 1];
    if (last.id !== this.playbackIdCounter) return;
    let reason = '无';
    if (pageAllowsAudio) {
      if (ConfigStore.allowTexts.includes('预约单') || ConfigStore.allowTexts.includes('专车舒适') || ConfigStore.allowTexts.includes('不抢')) {
        reason = '预约单';
      } else if (ConfigStore.allowTexts.includes('实时单') || ConfigStore.allowTexts.includes('接乘客')) {
        reason = '实时单';
      } else {
        reason = '通知同时';
      }
    }
    ConfigStore.playbackRecords[ConfigStore.playbackRecords.length - 1] = new PlaybackRecord(
      last.id, last.startTime, last.endTime, last.duration, this.isMuted, reason
    );
    ConfigStore.save();
  }

  private ensureMuted(): void {
    if (this.isMuted) return;
    try {
      if (!this.audioManager) return;
      this.originalVolume = this.audioManager.getVolume(audio.AudioVolumeType.MEDIA);
      this.audioManager.setVolume(audio.AudioVolumeType.MEDIA, 0);
      this.isMuted = true;
      Logger.info(TAG, '### MUTED ###');
    } catch (e) {
      Logger.error(TAG, 'mute failed', e as Error);
    }
  }

  private ensureUnmuted(): void {
    if (!this.isMuted) return;
    try {
      if (!this.audioManager) return;
      const vol = this.originalVolume >= 0 ? this.originalVolume :
        this.audioManager.getVolume(audio.AudioVolumeType.MEDIA);
      this.audioManager.setVolume(audio.AudioVolumeType.MEDIA, vol > 0 ? vol : 8);
      this.isMuted = false;
      Logger.info(TAG, '### UNMUTED ###');
    } catch (e) {
      Logger.error(TAG, 'unmute failed', e as Error);
    }
  }

  private pullDidiToForeground(): void {
    try {
      const want: Want = {
        bundleName: DIDI_BUNDLE_NAME,
        abilityName: ''
      };
      this.context.startAbility(want);
      Logger.info(TAG, 'Pulled DIDI to foreground');
    } catch (e) {
      Logger.error(TAG, 'Failed to pull DIDI foreground', e as Error);
    }
    EventBus.emit(EventId.REQUEST_PAGE_CHECK);
  }

  private broadcastState(): void {
    const payload: PlaybackStateEvent = { isPlaying: this.isPlaying, isMuted: this.isMuted };
    EventBus.emit(EventId.PLAYBACK_STATE_CHANGED, payload);
  }

  private startPositionCheck(): void {
    this.stopPositionCheck();
    const interval = ConfigStore.positionCheckIntervalMs;
    this.positionCheckTimer = setInterval(() => {
      if (this.state === PlaybackState.PLAYING) {
        const timeSinceLast = Date.now() - this.lastActivityTime;
        if (timeSinceLast >= ConfigStore.silenceThresholdMs) {
          if (this.positionStableStartTime === 0) {
            this.positionStableStartTime = Date.now();
          }
          const stableDuration = Date.now() - this.positionStableStartTime;
          if (stableDuration >= ConfigStore.silenceThresholdMs) {
            this.handleSilence();
          }
        } else {
          this.positionStableStartTime = 0;
        }
        if (Date.now() - this.playbackStartTime >= ConfigStore.maxPlaybackDurationMs) {
          this.handleSilence();
        }
      }
    }, interval);
  }

  private stopPositionCheck(): void {
    if (this.positionCheckTimer !== -1) {
      clearInterval(this.positionCheckTimer);
      this.positionCheckTimer = -1;
    }
  }

  private shutdown(): void {
    this.stopPositionCheck();
    this.ensureUnmuted();
    try {
      this.audioManager?.off('audioRendererChange', this.rendererCallback);
    } catch (e) {
      Logger.error(TAG, 'off renderer callback failed', e as Error);
    }
    EventBus.off(EventId.CHECK_PAGE_RESULT);
    EventBus.off(EventId.ENABLED_CHANGED);
    this.context.terminateSelf();
  }

  private async createNotificationChannel(): Promise<void> {
    try {
      await notificationManager.addSlot({
        type: notificationManager.SlotType.SOCIAL_COMMUNICATION,
        level: notificationManager.SlotLevel.LEVEL_LOW,
        desc: '音频监控前台服务',
        badgeFlag: false,
        sound: '',
        vibrationEnabled: false
      });
    } catch (e) {
      Logger.error(TAG, 'addSlot failed', e as Error);
    }
  }

  private async startForeground(): Promise<void> {
    try {
      const wantAgentInfo: wantAgent.WantAgentInfo = {
        wants: [
          {
            bundleName: this.context.applicationInfo.name,
            abilityName: 'MainEntry'
          }
        ],
        operationType: wantAgent.OperationType.START_ABILITY,
        requestCode: 0
      };
      const wa = await wantAgent.getWantAgent(wantAgentInfo);
      const request: notificationManager.NotificationRequest = {
        id: NOTIFICATION_ID,
        content: {
          contentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
          normal: {
            title: '滴滴静音器',
            text: '音频监控中',
            additionalText: ''
          }
        },
        notificationSlotType: notificationManager.SlotType.SOCIAL_COMMUNICATION,
        wantAgent: wa
      };
      await notificationManager.publish(request);
    } catch (e) {
      Logger.error(TAG, 'startForeground publish failed', e as Error);
    }
  }
}
