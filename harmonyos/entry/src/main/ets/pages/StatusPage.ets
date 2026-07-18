import router from '@ohos.router';
import { EventBus, EventId, PlaybackStateEvent } from '../utils/EventBus';
import { ConfigStore } from '../utils/ConfigStore';

@Entry
@Component
struct StatusPage {
  @State isPlaying: boolean = false;
  @State isMuted: boolean = false;
  @State enabled: boolean = true;
  @State allowTextCount: number = 0;
  @State blockHintCount: number = 0;

  aboutToAppear() {
    ConfigStore.init().then(() => {
      this.enabled = ConfigStore.enabled;
      this.allowTextCount = ConfigStore.allowTexts.length;
      this.blockHintCount = ConfigStore.blockHints.length;
    });
    EventBus.on(EventId.PLAYBACK_STATE_CHANGED, (event) => {
      const data = event.data as PlaybackStateEvent;
      this.isPlaying = data.isPlaying;
      this.isMuted = data.isMuted;
    });
  }

  aboutToDisappear() {
    EventBus.off(EventId.PLAYBACK_STATE_CHANGED);
  }

  build() {
    Column() {
      Text('系统状态')
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .margin({ bottom: 16 })

      this.StatusRow('总开关', this.enabled ? '开启' : '关闭', this.enabled ? '#4CAF50' : '#F44336')
      this.StatusRow('音频状态', this.isPlaying ? '播报中' : '空闲', this.isPlaying ? '#2196F3' : '#666666')
      this.StatusRow('静音状态', this.isMuted ? '静音中' : '未静音', this.isMuted ? '#F44336' : '#4CAF50')
      this.StatusRow('放行关键词数', `${this.allowTextCount}`, '#666666')
      this.StatusRow('黑名单关键词数', `${this.blockHintCount}`, '#666666')

      Button('返回')
        .width('100%')
        .margin({ top: 20 })
        .onClick(() => router.back())
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }

  @Builder
  StatusRow(label: string, value: string, color: string) {
    Row() {
      Text(label)
        .fontSize(16)
        .layoutWeight(1)
      Text(value)
        .fontSize(16)
        .fontColor(color)
    }
    .width('100%')
    .padding(12)
    .margin({ bottom: 8 })
    .backgroundColor('#F5F5F5')
    .borderRadius(8)
  }
}
