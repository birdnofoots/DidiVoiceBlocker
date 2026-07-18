import dataPreferences from '@ohos.data.preferences';
import { PlaybackRecord } from '../model/PlaybackRecord';
import { EventBus, EventId } from './EventBus';
import { Logger } from './Logger';

export class ConfigStore {
  private static readonly PREFS_NAME = 'blocker_config';
  private static readonly KEY_ENABLED = 'enabled';
  private static readonly KEY_ALLOW_TEXTS = 'allow_texts';
  private static readonly KEY_ALLOW_RESOURCE_IDS = 'allow_resource_ids';
  private static readonly KEY_BLOCK_HINTS = 'block_hints';
  private static readonly KEY_PLAYBACK_RECORDS = 'playback_records';
  private static readonly KEY_MUTE_STATS = 'mute_stats';

  private static readonly KEY_SILENCE_THRESHOLD = 'silenceThresholdMs';
  private static readonly KEY_POSITION_INTERVAL = 'positionCheckIntervalMs';
  private static readonly KEY_MAX_DURATION = 'maxPlaybackDurationMs';
  private static readonly KEY_SCAN_TIMEOUT = 'scanTimeoutMs';
  private static readonly KEY_DEBOUNCE = 'debounceMs';
  private static readonly KEY_PAGE_SCAN_DELAY = 'pageScanDelayMs';

  private static defaultAllowTexts: string[] = ['预约单', '专车舒适', '不抢', '实时单', '接乘客'];
  private static defaultAllowResourceIds: string[] = [
    'as_widget_realtime_order_title_text',
    'layout_booking_order_list_item',
    'btn_book_list_tel',
    'as_icon_booking',
    'activity_order_confirm',
    'activity_order_review',
    'fragment_order_review',
    'fragment_order_qr',
    'order_review_head_bar_view',
    'order_review_head_tag_start',
    'order_review_head_tag_end',
    'v_order_review_head_bar',
    'activity_order_trip_end',
    'travel_detail_order_info_address_from_txt',
    'travel_detail_order_info_address_to_txt',
    'travel_detail_order_info_passenger_count',
    'travel_detail_order_info_im_layout',
    'travel_detail_order_info_phone_btn',
    'layout_selfdriving_bottom_order_detail_view'
  ];
  private static defaultBlockHints: string[] = [
    'SafetyReminder', 'FatigueAlert', 'DrivingTime',
    'RestReminder', 'SafetyDialog', '安全提醒',
    '疲劳驾驶', '请注意休息', '注意安全', '休息提醒'
  ];

  private static prefs: dataPreferences.Preferences | null = null;
  private static inited: boolean = false;

  static enabled: boolean = true;
  static allowTexts: string[] = [...ConfigStore.defaultAllowTexts];
  static allowResourceIds: string[] = [...ConfigStore.defaultAllowResourceIds];
  static blockHints: string[] = [...ConfigStore.defaultBlockHints];
  static playbackRecords: PlaybackRecord[] = [];

  static silenceThresholdMs: number = 3000;
  static positionCheckIntervalMs: number = 500;
  static maxPlaybackDurationMs: number = 30000;
  static scanTimeoutMs: number = 5000;
  static debounceMs: number = 200;
  static pageScanDelayMs: number = 250;

  static async init(): Promise<void> {
    if (ConfigStore.inited) return;
    const context = getContext().getApplicationContext();
    ConfigStore.prefs = await dataPreferences.getPreferences(context, ConfigStore.PREFS_NAME);
    await ConfigStore.reload();
    ConfigStore.inited = true;
  }

  static async reload(): Promise<void> {
    const p = ConfigStore.prefs;
    if (!p) return;
    ConfigStore.enabled = await p.get(ConfigStore.KEY_ENABLED, true) as boolean;
    ConfigStore.allowTexts = await p.get(ConfigStore.KEY_ALLOW_TEXTS, ConfigStore.defaultAllowTexts) as string[];
    ConfigStore.allowResourceIds = await p.get(ConfigStore.KEY_ALLOW_RESOURCE_IDS, ConfigStore.defaultAllowResourceIds) as string[];
    ConfigStore.blockHints = await p.get(ConfigStore.KEY_BLOCK_HINTS, ConfigStore.defaultBlockHints) as string[];

    ConfigStore.silenceThresholdMs = await p.get(ConfigStore.KEY_SILENCE_THRESHOLD, 3000) as number;
    ConfigStore.positionCheckIntervalMs = await p.get(ConfigStore.KEY_POSITION_INTERVAL, 500) as number;
    ConfigStore.maxPlaybackDurationMs = await p.get(ConfigStore.KEY_MAX_DURATION, 30000) as number;
    ConfigStore.scanTimeoutMs = await p.get(ConfigStore.KEY_SCAN_TIMEOUT, 5000) as number;
    ConfigStore.debounceMs = await p.get(ConfigStore.KEY_DEBOUNCE, 200) as number;
    ConfigStore.pageScanDelayMs = await p.get(ConfigStore.KEY_PAGE_SCAN_DELAY, 250) as number;

    const recordsJson = await p.get(ConfigStore.KEY_PLAYBACK_RECORDS, '[]') as string;
    ConfigStore.playbackRecords = ConfigStore.parseRecords(recordsJson);
  }

  static async save(): Promise<void> {
    const p = ConfigStore.prefs;
    if (!p) return;
    await p.put(ConfigStore.KEY_ENABLED, ConfigStore.enabled);
    await p.put(ConfigStore.KEY_ALLOW_TEXTS, ConfigStore.allowTexts);
    await p.put(ConfigStore.KEY_ALLOW_RESOURCE_IDS, ConfigStore.allowResourceIds);
    await p.put(ConfigStore.KEY_BLOCK_HINTS, ConfigStore.blockHints);
    await p.put(ConfigStore.KEY_PLAYBACK_RECORDS, JSON.stringify(ConfigStore.playbackRecords.map(r => r.toJSON())));
    await p.flush();
  }

  static async setEnabled(value: boolean): Promise<void> {
    ConfigStore.enabled = value;
    await ConfigStore.save();
    EventBus.emit(EventId.ENABLED_CHANGED, { enabled: value });
  }

  static async addAllowText(text: string): Promise<void> {
    if (!ConfigStore.allowTexts.includes(text)) {
      ConfigStore.allowTexts.push(text);
      await ConfigStore.save();
    }
  }

  static async removeAllowText(text: string): Promise<void> {
    ConfigStore.allowTexts = ConfigStore.allowTexts.filter(t => t !== text);
    await ConfigStore.save();
  }

  static async addAllowResourceId(id: string): Promise<void> {
    if (!ConfigStore.allowResourceIds.includes(id)) {
      ConfigStore.allowResourceIds.push(id);
      await ConfigStore.save();
    }
  }

  static async removeAllowResourceId(id: string): Promise<void> {
    ConfigStore.allowResourceIds = ConfigStore.allowResourceIds.filter(t => t !== id);
    await ConfigStore.save();
  }

  static async addBlockHint(hint: string): Promise<void> {
    if (!ConfigStore.blockHints.includes(hint)) {
      ConfigStore.blockHints.push(hint);
      await ConfigStore.save();
    }
  }

  static async removeBlockHint(hint: string): Promise<void> {
    ConfigStore.blockHints = ConfigStore.blockHints.filter(t => t !== hint);
    await ConfigStore.save();
  }

  static async addPlaybackRecord(record: PlaybackRecord): Promise<void> {
    ConfigStore.playbackRecords.push(record);
    if (ConfigStore.playbackRecords.length > 2000) {
      ConfigStore.playbackRecords = ConfigStore.playbackRecords.slice(-2000);
    }
    await ConfigStore.save();
  }

  static async clearPlaybackRecords(): Promise<void> {
    ConfigStore.playbackRecords = [];
    await ConfigStore.save();
  }

  static async saveAudioParams(
    silenceThreshold: number,
    positionInterval: number,
    maxDuration: number,
    scanTimeout: number,
    debounce: number,
    pageScanDelay: number
  ): Promise<void> {
    ConfigStore.silenceThresholdMs = silenceThreshold;
    ConfigStore.positionCheckIntervalMs = positionInterval;
    ConfigStore.maxPlaybackDurationMs = maxDuration;
    ConfigStore.scanTimeoutMs = scanTimeout;
    ConfigStore.debounceMs = debounce;
    ConfigStore.pageScanDelayMs = pageScanDelay;
    const p = ConfigStore.prefs;
    if (!p) return;
    await p.put(ConfigStore.KEY_SILENCE_THRESHOLD, silenceThreshold);
    await p.put(ConfigStore.KEY_POSITION_INTERVAL, positionInterval);
    await p.put(ConfigStore.KEY_MAX_DURATION, maxDuration);
    await p.put(ConfigStore.KEY_SCAN_TIMEOUT, scanTimeout);
    await p.put(ConfigStore.KEY_DEBOUNCE, debounce);
    await p.put(ConfigStore.KEY_PAGE_SCAN_DELAY, pageScanDelay);
    await p.flush();
    EventBus.emit(EventId.AUDIO_PARAMS_UPDATED);
  }

  static matchesWildcard(text: string, pattern: string): boolean {
    if (pattern.length === 0) return text.length === 0;
    if (!pattern.includes('*') && !pattern.includes('?')) {
      return text.toLowerCase().includes(pattern.toLowerCase());
    }
    const regex = pattern
      .replace(/\\/g, '\\\\')
      .replace(/\./g, '\\.')
      .replace(/\*/g, '.*')
      .replace(/\?/g, '.');
    try {
      return new RegExp(regex, 'i').test(text);
    } catch (e) {
      return text.toLowerCase().includes(pattern.toLowerCase());
    }
  }

  private static parseRecords(json: string): PlaybackRecord[] {
    try {
      const arr = JSON.parse(json) as object[];
      return arr.map(o => PlaybackRecord.fromJSON(o));
    } catch (e) {
      Logger.error('ConfigStore', 'parseRecords failed', e as Error);
      return [];
    }
  }
}
