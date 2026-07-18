import router from '@ohos.router';
import { ConfigStore } from '../utils/ConfigStore';
import { PlaybackRecord } from '../model/PlaybackRecord';

@Entry
@Component
struct RecordsPage {
  @State records: PlaybackRecord[] = [];

  aboutToAppear() {
    ConfigStore.init().then(() => {
      this.records = [...ConfigStore.playbackRecords].reverse();
    });
  }

  build() {
    Column() {
      Text('播报记录')
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .margin({ bottom: 16 })

      List() {
        ForEach(this.records, (record: PlaybackRecord) => {
          ListItem() {
            Column() {
              Row() {
                Text(`#${record.id}`)
                  .fontSize(14)
                  .fontWeight(FontWeight.Bold)
                Text(record.wasMuted ? '已静音' : '已放行')
                  .fontSize(14)
                  .fontColor(record.wasMuted ? '#F44336' : '#4CAF50')
              }
              .width('100%')
              .justifyContent(FlexAlign.SpaceBetween)

              Text(`时长: ${record.duration}ms | 原因: ${record.allowReason}`)
                .fontSize(12)
                .fontColor('#666666')
                .margin({ top: 4 })
            }
            .width('100%')
            .padding(12)
            .backgroundColor('#F5F5F5')
            .borderRadius(8)
            .margin({ bottom: 8 })
          }
        })
      }
      .width('100%')
      .layoutWeight(1)

      Button('清空记录')
        .width('100%')
        .margin({ bottom: 8 })
        .onClick(() => {
          ConfigStore.clearPlaybackRecords().then(() => {
            this.records = [];
          });
        })

      Button('返回')
        .width('100%')
        .onClick(() => router.back())
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }
}
