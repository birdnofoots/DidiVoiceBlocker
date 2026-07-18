import router from '@ohos.router';
import promptAction from '@ohos.promptAction';
import { ConfigStore } from '../utils/ConfigStore';

@Entry
@Component
struct WhitelistPage {
  @State allowTexts: string[] = [];
  @State blockHints: string[] = [];
  @State newText: string = '';

  aboutToAppear() {
    ConfigStore.init().then(() => {
      this.allowTexts = [...ConfigStore.allowTexts];
      this.blockHints = [...ConfigStore.blockHints];
    });
  }

  build() {
    Column() {
      Text('白名单管理')
        .fontSize(20)
        .fontWeight(FontWeight.Bold)
        .margin({ bottom: 16 })

      Text('放行关键词（检测到即放行，不静音）')
        .fontSize(14)
        .width('100%')
        .margin({ bottom: 8 })

      Row() {
        TextInput({ placeholder: '输入关键词', text: $$this.newText })
          .layoutWeight(1)
          .onChange((value: string) => this.newText = value)
        Button('添加')
          .margin({ left: 8 })
          .onClick(() => {
            if (this.newText.trim().length === 0) return;
            ConfigStore.addAllowText(this.newText.trim()).then(() => {
              this.allowTexts = [...ConfigStore.allowTexts];
              this.newText = '';
            });
          })
      }
      .width('100%')
      .margin({ bottom: 12 })

      List() {
        ForEach(this.allowTexts, (text: string) => {
          ListItem() {
            Row() {
              Text(text)
                .layoutWeight(1)
              Button('删除')
                .fontSize(12)
                .onClick(() => {
                  ConfigStore.removeAllowText(text).then(() => {
                    this.allowTexts = [...ConfigStore.allowTexts];
                  });
                })
            }
            .width('100%')
            .padding(12)
          }
        })
      }
      .width('100%')
      .layoutWeight(1)
      .divider({ strokeWidth: 1, color: '#EEEEEE' })

      Button('返回')
        .width('100%')
        .margin({ top: 16 })
        .onClick(() => router.back())
    }
    .width('100%')
    .height('100%')
    .padding(16)
  }
}
