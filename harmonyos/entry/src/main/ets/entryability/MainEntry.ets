import UIAbility from '@ohos.app.ability.UIAbility';
import type Want from '@ohos.app.ability.Want';
import type AbilityConstant from '@ohos.app.ability.AbilityConstant';
import type window from '@ohos.window';
import { Logger } from '../utils/Logger';

export default class MainEntry extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    Logger.info('MainEntry', 'onCreate');
  }

  onDestroy(): void {
    Logger.info('MainEntry', 'onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    Logger.info('MainEntry', 'onWindowStageCreate');
    windowStage.loadContent('pages/Index', (err) => {
      if (err && err.code) {
        Logger.error('MainEntry', 'Failed to load content', err);
        return;
      }
      Logger.info('MainEntry', 'Succeeded in loading content.');
    });
  }

  onWindowStageDestroy(): void {
    Logger.info('MainEntry', 'onWindowStageDestroy');
  }

  onForeground(): void {
    Logger.info('MainEntry', 'onForeground');
  }

  onBackground(): void {
    Logger.info('MainEntry', 'onBackground');
  }
}
