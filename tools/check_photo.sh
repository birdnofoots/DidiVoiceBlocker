#!/bin/bash
# check_photo.sh — 用 ADB 检查出车拍照状态
# 路径: DIDI 首页 → 全部工具 → 出车拍照 → 读"审核中" → BACK 返回
# 结果写入 DriverPhotoStore prefs + 发送刷新广播

set -e
SERIAL="${1:-$(cat /root/adb-helper/.active_serial 2>/dev/null || echo 192.168.1.174:5555)}"
ADB="adb -s $SERIAL"

echo "[check_photo] serial=$SERIAL"

# 1) 确保 DIDI 在首页
FOCUS=$($ADB shell "dumpsys window 2>/dev/null | grep mCurrentFocus" | head -1)
if ! echo "$FOCUS" | grep -q "com.sdu.didi.gsui"; then
    echo "[check_photo] DIDI 不在前台,启动..."
    $ADB shell "monkey -p com.sdu.didi.gsui -c android.intent.category.LAUNCHER 1" 2>/dev/null
    sleep 3
fi

# 如果不在首页,BACK 几次退回
for i in 1 2 3; do
    F=$($ADB shell "dumpsys window 2>/dev/null | grep mCurrentFocus" | head -1)
    if echo "$F" | grep -q "MainActivity"; then
        echo "[check_photo] 已在首页"
        break
    fi
    $ADB shell input keyevent BACK
    sleep 1
done

# 2) 点"全部工具"(1094, 1067)
echo "[check_photo] tap 全部工具 (1094,1067)"
$ADB shell input tap 1094 1067
sleep 3

# 3) 点"出车拍照"(463, 2037)
echo "[check_photo] tap 出车拍照 (463,2037)"
$ADB shell input tap 463 2037
sleep 3

# 4) 读页面,找"审核中"
$ADB shell uiautomator dump /sdcard/u.xml 2>/dev/null
$ADB pull /sdcard/u.xml /tmp/photo_check.xml >/dev/null 2>&1
$ADB shell rm /sdcard/u.xml 2>/dev/null

COMPLETED=$(python3 -c "
import re
xml = open('/tmp/photo_check.xml').read()
texts = set(re.findall(r'\btext=\"([^\"]+)\"', xml))
print('true' if '审核中' in texts else 'false')
")
echo "[check_photo] 审核中=$COMPLETED"

# 5) 写 prefs(用 push + run-as cp,避免 shell echo 破坏 XML)
PHOTO_VAL="$COMPLETED"
DAY_NUM=$(date +%j)
cat > /tmp/photo_prefs.xml << XML
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <boolean name="photo_completed" value="$PHOTO_VAL" />
    <int name="photo_checked_day" value="$DAY_NUM" />
</map>
XML
$ADB push /tmp/photo_prefs.xml /data/local/tmp/photo_prefs.xml >/dev/null 2>&1
$ADB shell "run-as com.didi.voiceblocker cp /data/local/tmp/photo_prefs.xml shared_prefs/driver_photo.xml" 2>/dev/null

# 发送刷新广播
$ADB shell "am broadcast -a com.didi.voiceblocker.REFRESH_DISPLAY -p com.didi.voiceblocker" 2>/dev/null | tail -1

# 6) BACK 两次返回
$ADB shell input keyevent BACK
sleep 1
$ADB shell input keyevent BACK

echo "[check_photo] 完成: 出车拍照=$(if [ "$COMPLETED" = "true" ]; then echo '✅已拍照'; else echo '❌未完成'; fi)"
