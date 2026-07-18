import AccessibilityExtensionAbility from '@ohos.application.AccessibilityExtensionAbility';
import accessibility from '@ohos.accessibility';
import { Logger } from '../utils/Logger';

const TAG = 'DashboardAccessibilityAbility';
const DIDI_BUNDLE_NAME = 'com.sdu.didi.gsui';

// Phase 1 MVP 占位：后续实现读取“接单数”节点与导航回首页
export default class DashboardAccessibilityAbility extends AccessibilityExtensionAbility {
  onConnect(): void {
    Logger.info(TAG, 'onConnect');
  }

  onDisconnect(): void {
    Logger.info(TAG, 'onDisconnect');
  }

  onAccessibilityEvent(event: accessibility.AccessibilityEvent): void {
    if (!event || event.bundleName !== DIDI_BUNDLE_NAME) return;
    Logger.info(TAG, `event type=${event.eventType}`);
  }

  onDestroy(): void {
    Logger.info(TAG, 'onDestroy');
  }
}
