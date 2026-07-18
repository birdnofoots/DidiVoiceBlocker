import emitter from '@ohos.events.emitter';

export enum EventId {
  PLAYBACK_STATE_CHANGED = 1001,
  MUTE_STATE_CHANGED = 1002,
  ENABLED_CHANGED = 1003,
  AUDIO_PARAMS_UPDATED = 1004,
  CHECK_PAGE_RESULT = 1005,
  REQUEST_PAGE_CHECK = 1006,
  REFRESH_ORDERS = 1007,
  REFRESH_DISPLAY = 1008,
}

export interface PlaybackStateEvent {
  isPlaying: boolean;
  isMuted: boolean;
}

export interface PageCheckResultEvent {
  pageAllowsAudio: boolean;
}

export class EventBus {
  static on(eventId: EventId, callback: (eventData: emitter.EventData) => void): void {
    emitter.on({ eventId }, callback);
  }

  static once(eventId: EventId, callback: (eventData: emitter.EventData) => void): void {
    emitter.once({ eventId }, callback);
  }

  static off(eventId: EventId, callback?: (eventData: emitter.EventData) => void): void {
    if (callback) {
      emitter.off({ eventId }, callback);
    } else {
      emitter.off({ eventId });
    }
  }

  static emit(eventId: EventId, data?: object): void {
    emitter.emit({ eventId, data });
  }
}
