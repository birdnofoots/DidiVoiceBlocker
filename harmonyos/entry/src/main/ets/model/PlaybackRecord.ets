export class PlaybackRecord {
  id: number = 0;
  startTime: number = 0;
  endTime: number = 0;
  duration: number = 0;
  wasMuted: boolean = false;
  allowReason: string = '无';

  constructor(id: number, startTime: number, endTime: number, duration: number,
    wasMuted: boolean, allowReason: string) {
    this.id = id;
    this.startTime = startTime;
    this.endTime = endTime;
    this.duration = duration;
    this.wasMuted = wasMuted;
    this.allowReason = allowReason;
  }

  toJSON(): object {
    return {
      id: this.id,
      startTime: this.startTime,
      endTime: this.endTime,
      duration: this.duration,
      wasMuted: this.wasMuted,
      allowReason: this.allowReason
    };
  }

  static fromJSON(obj: object): PlaybackRecord {
    return new PlaybackRecord(
      (obj as Record<string, number | boolean | string>).id as number ?? 0,
      (obj as Record<string, number | boolean | string>).startTime as number ?? 0,
      (obj as Record<string, number | boolean | string>).endTime as number ?? 0,
      (obj as Record<string, number | boolean | string>).duration as number ?? 0,
      (obj as Record<string, number | boolean | string>).wasMuted as boolean ?? false,
      (obj as Record<string, number | boolean | string>).allowReason as string ?? '无'
    );
  }
}
