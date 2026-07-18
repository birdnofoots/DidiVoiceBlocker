import AccessibilityExtensionAbility from '@ohos.application.AccessibilityExtensionAbility';
import accessibility from '@ohos.accessibility';
import { BusinessError } from '@ohos.base';
import { ConfigStore } from '../utils/ConfigStore';
import { EventBus, EventId } from '../utils/EventBus';
import { Logger } from '../utils/Logger';

const TAG = 'SmartVoiceBlocker';
const DIDI_BUNDLE_NAME = 'com.sdu.didi.gsui';
const MAX_RETRY = 3;

export default class SmartVoiceBlockerAbility extends AccessibilityExtensionAbility {
  private connected: boolean = false;
  private lastScanTime: number = 0;

  onConnect(): void {
    Logger.info(TAG, 'onConnect');
    this.connected = true;
    ConfigStore.init();
    EventBus.on(EventId.REQUEST_PAGE_CHECK, () => {
      this.checkCurrentPageAndNotify();
    });
  }

  onDisconnect(): void {
    Logger.info(TAG, 'onDisconnect');
    this.connected = false;
    EventBus.off(EventId.REQUEST_PAGE_CHECK);
  }

  onAccessibilityEvent(event: accessibility.AccessibilityEvent): void {
    if (!event || event.bundleName !== DIDI_BUNDLE_NAME) return;
    const now = Date.now();
    if (now - this.lastScanTime < ConfigStore.debounceMs) return;
    this.lastScanTime = now;
    // 窗口变化时记录日志，实际扫描由 REQUEST_PAGE_CHECK 触发
    Logger.info(TAG, `onAccessibilityEvent type=${event.eventType} class=${event.className ?? 'null'}`);
  }

  onDestroy(): void {
    Logger.info(TAG, 'onDestroy');
    EventBus.off(EventId.REQUEST_PAGE_CHECK);
  }

  private checkCurrentPageAndNotify(): void {
    Logger.info(TAG, '>>> checkCurrentPageAndNotify');
    this.tryScanWithRetry(0);
  }

  private tryScanWithRetry(attempt: number): void {
    const root = this.tryGetRoot();
    if (root && root.bundleName === DIDI_BUNDLE_NAME) {
      Logger.info(TAG, 'root accessible, scan now');
      const start = Date.now();
      const allows = this.scanForAllowConditions(root);
      Logger.info(TAG, `page_check_total=${Date.now() - start}ms allows=${allows}`);
      this.notifyPageResult(allows);
      this.releaseElement(root);
      return;
    }
    if (root) {
      this.releaseElement(root);
    }
    if (attempt >= MAX_RETRY) {
      Logger.warn(TAG, 'retry_exhausted, no_root');
      this.notifyPageResult(false);
      return;
    }
    Logger.info(TAG, `root_not_accessible, retry ${attempt + 1}/${MAX_RETRY} in ${ConfigStore.pageScanDelayMs}ms`);
    setTimeout(() => this.tryScanWithRetry(attempt + 1), ConfigStore.pageScanDelayMs);
  }

  private tryGetRoot(): accessibility.AccessibilityElement | null {
    try {
      // HarmonyOS 提供 ElementOperator 查询当前窗口根节点
      // 实际 API 可能为 getRootElement() 或 getRoot(windowMode)
      const operator = accessibility.ElementOperator.getInstance();
      return operator.getRootElement();
    } catch (e) {
      Logger.error(TAG, 'getRoot failed', e as Error);
      return null;
    }
  }

  private scanForAllowConditions(node: accessibility.AccessibilityElement): boolean {
    let foundBlacklist = false;
    let foundWhitelist = false;

    const recurse = (n: accessibility.AccessibilityElement) => {
      const text = n.accessibilityText ?? '';
      const desc = n.accessibilityDescription ?? '';
      const combined = `${text} ${desc}`;

      for (const hint of ConfigStore.blockHints) {
        if (ConfigStore.matchesWildcard(combined, hint)) {
          foundBlacklist = true;
        }
      }
      for (const allowed of ConfigStore.allowTexts) {
        if (ConfigStore.matchesWildcard(combined, allowed)) {
          foundWhitelist = true;
        }
      }
      // 鸿蒙无障碍节点通常没有 resourceId，allowResourceIds 在此失效或需额外适配
      for (const allowedId of ConfigStore.allowResourceIds) {
        if (n.viewIdResourceName && ConfigStore.matchesWildcard(n.viewIdResourceName, allowedId)) {
          foundWhitelist = true;
        }
      }

      const childCount = n.childCount ?? 0;
      for (let i = 0; i < childCount; i++) {
        const child = this.getChild(n, i);
        if (child) {
          recurse(child);
          this.releaseElement(child);
        }
      }
    };

    recurse(node);
    const allows = !foundBlacklist && foundWhitelist;
    Logger.info(TAG, `scan: blacklist=${foundBlacklist} whitelist=${foundWhitelist} allows=${allows}`);
    return allows;
  }

  private getChild(node: accessibility.AccessibilityElement, index: number): accessibility.AccessibilityElement | null {
    try {
      // API 可能为 node.getChild(index) 或 ElementOperator.getChild(node, index)
      const operator = accessibility.ElementOperator.getInstance();
      return operator.getChild(node, index);
    } catch (e) {
      return null;
    }
  }

  private releaseElement(node: accessibility.AccessibilityElement | null): void {
    if (!node) return;
    try {
      // API 可能为 node.release()
      node.release();
    } catch (e) {
      // ignore
    }
  }

  private notifyPageResult(allowsAudio: boolean): void {
    EventBus.emit(EventId.CHECK_PAGE_RESULT, { pageAllowsAudio: allowsAudio });
  }
}
