import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import bundleManager from '@ohos.bundle.bundleManager';
import abilityAccessCtrl from '@ohos.abilityAccessCtrl';
import { ConfigStore } from '../utils/ConfigStore';
import { EventBus, EventId, PlaybackStateEvent } from '../utils/EventBus';
import { Logger } from '../utils/Logger';

const TAG = 'Index';

@Entry
@Component
struct Index {
  @State enabled: boolean = true;
  @State isPlaying: boolean = false;
  @State isMuted: boolean = false;
  @State serviceRunning: boolean = false;
  @State accessibilityEnabled: boolean = false;
  @State overlayEnabled: boolean = false;

  aboutToAppear() {
    ConfigStore.init().then(() => {
      this.enabled = ConfigStore.enabled;
      this.checkPermissions();
      EventBus.on(EventId.PLAYBACK_STATE_CHANGED, (event) => {
        const data = event.data as PlaybackStateEvent;
        this.isPlaying = data.isPlaying;
        this.isMuted = data.isMuted;
      });
    });
  }

  aboutToDisappear() {
    EventBus.off(EventId.PLAYBACK_STATE_CHANGED);
  }

  async checkPermissions(): Promise<void> {
    try {
      // 检查悬浮窗权限（顶层窗口）
      const atManager = abilityAccessCtrl.createAtManager();
      const overlayGrant = await atManager.checkAccessToken(this.getTokenId(), 'ohos.permission.WINDOW_TOP_MOST');
      this.overlayEnabled = overlayGrant === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
      // 无障碍权限无法通过 API 直接查询是否开启，需引导用户去设置
      this.accessibilityEnabled = false;
    } catch (e) {
      Logger.error(TAG, 'checkPermissions failed', e as Error);
    }
  }

  private getTokenId(): number {
    try {
      return bundleManager.getBundleInfoForSelf(bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION).appInfo.accessTokenId;
    } catch (e) {
      return 0;
    }
  }

  build() {
    Column() {
      Text('滴滴静音器')
        .fontSize(24)
        .fontWeight(FontWeight.Bold)
        .margin({ top: 20, bottom: 10 })

      Text(this.getStatusText())
        .fontSize(16)
        .fontColor(this.isMuted ? '#F44336' : '#4CAF50')
        .margin({ bottom: 20 })

      Row() {
        Text('总开关')
          .fontSize(18)
          .layoutWeight(1)
        Toggle({ type: ToggleType.Switch, isOn: this.enabled })
          .onChange((isOn: boolean) => {
            ConfigStore.setEnabled(isOn);
            this.enabled = isOn;
            if (isOn) {
              this.startAudioService();
            }
          })
      }
      .width('100%')
      .padding(16)
      .backgroundColor('#F5F5F5')
      .borderRadius(8)
      .margin({ bottom: 12 })

      Button('启动音频监控服务')
        .width('100%')
        .margin({ bottom: 8 })
        .enabled(this.enabled)
        .onClick(() => this.startAudioService())

      Button('停止音频监控服务')
        .width('100%')
        .margin({ bottom: 16 })
        .onClick(() => this.stopAudioService())

      Text('权限状态')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .width('100%')
        .margin({ bottom: 8 })

      this.PermissionRow('无障碍服务', this.accessibilityEnabled, () => this.openAccessibilitySettings())
      this.PermissionRow('悬浮窗权限', this.overlayEnabled, () => this.openOverlaySettings())

      Row() {
        Button('白名单管理')
          .layoutWeight(1)
          .margin({ right: 8 })
          .onClick(() => router.pushUrl({ url: 'pages/WhitelistPage' }))
        Button('播报记录')
          .layoutWeight(1)
          .margin({ right: 8 })
          .onClick(() => router.pushUrl({ url: 'pages/RecordsPage' }))
        Button('系统状态')
          .layoutWeight(1)
          .onClick(() => router.pushUrl({ url: 'pages/StatusPage' }))
      }
      .width('100%')
      .margin({ top: 20 })
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }

  @Builder
  PermissionRow(label: string, granted: boolean, onClick: () => void) {
    Row() {
      Text(label)
        .fontSize(16)
        .layoutWeight(1)
      Text(granted ? '已开启' : '未开启')
        .fontSize(14)
        .fontColor(granted ? '#4CAF50' : '#F44336')
        .margin({ right: 8 })
      Button('去开启')
        .fontSize(14)
        .enabled(!granted)
        .onClick(onClick)
    }
    .width('100%')
    .padding(12)
    .margin({ bottom: 8 })
    .backgroundColor('#FAFAFA')
    .borderRadius(8)
  }

  private getStatusText(): string {
    if (!this.enabled) return '已暂停';
    if (this.isPlaying && this.isMuted) return '🔇 播报中（已静音）';
    if (this.isPlaying && !this.isMuted) return '🔊 播报中（已放行）';
    if (this.isMuted) return '🔇 静音中';
    return '🔊 就绪';
  }

  private startAudioService(): void {
    try {
      const want = {
        bundleName: 'com.didi.voiceblocker.hmos',
        abilityName: 'AudioMonitorService'
      };
      this.context.startServiceExtensionAbility(want)
        .then(() => {
          this.serviceRunning = true;
          promptAction.showToast({ message: '音频监控服务已启动' });
        })
        .catch((err: Error) => {
          Logger.error(TAG, 'startService failed', err);
          promptAction.showToast({ message: '启动失败：' + err.message });
        });
    } catch (e) {
      Logger.error(TAG, 'startAudioService exception', e as Error);
    }
  }

  private stopAudioService(): void {
    try {
      const want = {
        bundleName: 'com.didi.voiceblocker.hmos',
        abilityName: 'AudioMonitorService',
        action: 'STOP'
      };
      this.context.stopServiceExtensionAbility(want)
        .then(() => {
          this.serviceRunning = false;
          promptAction.showToast({ message: '音频监控服务已停止' });
        })
        .catch((err: Error) => {
          Logger.error(TAG, 'stopService failed', err);
        });
    } catch (e) {
      Logger.error(TAG, 'stopAudioService exception', e as Error);
    }
  }

  private openAccessibilitySettings(): void {
    // HarmonyOS 无直接跳转 API，引导用户到设置 > 辅助功能 > 滴滴静音器
    promptAction.showToast({ message: '请前往 设置 > 辅助功能 > 无障碍 > 滴滴静音器 开启' });
  }

  private openOverlaySettings(): void {
    promptAction.showToast({ message: '请前往 设置 > 应用 > 滴滴静音器 > 悬浮窗权限 开启' });
  }
}
