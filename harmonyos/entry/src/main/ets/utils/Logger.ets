import fs from '@ohos.file.fs';

export class Logger {
  private static readonly TAG = 'DidiVoiceBlocker';
  private static readonly LOG_FILE = 'debug.log';
  private static logAppendCount = 0;

  static debug(tag: string, msg: string): void {
    console.debug(`[${Logger.TAG}][${tag}] ${msg}`);
  }

  static info(tag: string, msg: string): void {
    console.info(`[${Logger.TAG}][${tag}] ${msg}`);
    Logger.appendLog(tag, msg);
  }

  static warn(tag: string, msg: string): void {
    console.warn(`[${Logger.TAG}][${tag}] ${msg}`);
    Logger.appendLog(tag, `WARN: ${msg}`);
  }

  static error(tag: string, msg: string, err?: Error): void {
    console.error(`[${Logger.TAG}][${tag}] ${msg}`, err);
    Logger.appendLog(tag, `ERROR: ${msg} ${err ? err.message : ''}`);
  }

  private static appendLog(tag: string, msg: string): void {
    const context = getContext();
    if (!context) return;
    const dir = context.getApplicationContext().filesDir;
    const path = `${dir}/${Logger.LOG_FILE}`;
    const now = new Date();
    const ts = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}:${now.getSeconds().toString().padStart(2, '0')}.${now.getMilliseconds().toString().padStart(3, '0')}`;
    const line = `[${ts}][${tag}] ${msg}\n`;
    try {
      if (!fs.accessSync(path)) {
        fs.createSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE);
      }
      const file = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.APPEND);
      fs.writeSync(file.fd, line);
      fs.closeSync(file);
      Logger.logAppendCount++;
      if (Logger.logAppendCount >= 50) {
        Logger.logAppendCount = 0;
        Logger.trimLog(path);
      }
    } catch (e) {
      console.error('appendLog failed', e);
    }
  }

  private static trimLog(path: string): void {
    try {
      const file = fs.openSync(path, fs.OpenMode.READ_WRITE);
      const stat = fs.statSync(path);
      const buf = new ArrayBuffer(stat.size);
      fs.readSync(file.fd, buf);
      fs.closeSync(file);
      const text = String.fromCharCode.apply(null, Array.from(new Uint8Array(buf)));
      const lines = text.split('\n');
      if (lines.length > 2000) {
        const tail = lines.slice(-2000).join('\n') + '\n';
        const out = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
        const arr = new Uint8Array(Array.from(tail).map(c => c.charCodeAt(0)));
        fs.writeSync(out.fd, arr.buffer);
        fs.closeSync(out);
      }
    } catch (e) {
      console.error('trimLog failed', e);
    }
  }
}
