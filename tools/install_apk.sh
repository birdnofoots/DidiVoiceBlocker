#!/bin/bash
# install_apk.sh — 推送 APK 到华为手机(自动悬浮窗 + 弹窗处理 + 密码输入)
# 
# 流程:
#   1. 检查/强制 DIDI 变成悬浮窗(避免安装过程被 DiDi 播报打断)
#   2. adb install 触发华为安装弹窗
#   3. 自动点击"继续安装" → 勾选复选框 → 输入锁屏密码
#   4. 验证安装完成
#
# 依赖: mate60 helper (ADB 连接) / uiautomator dump (弹窗元素定位)
# 环境变量:
#   LOCKSCREEN_PIN  锁屏密码(数字),不设则交互式询问

set -e
SERIAL="$(cat /root/adb-helper/.active_serial 2>/dev/null || echo 192.168.43.1:5555)"
APK="${1:-/root/DidiVoiceBlocker/app/build/outputs/apk/debug/app-debug.apk}"
PIN="${LOCKSCREEN_PIN:-}"

# 颜色
err()  { echo -e "\033[31m[ERR]\033[0m  $*"; }
ok()   { echo -e "\033[32m[OK]\033[0m   $*"; }
warn() { echo -e "\033[33m[WARN]\033[0m $*"; }
step() { echo -e "\033[36m[$1]\033[0m $2"; }

ADB="/usr/bin/adb -s $SERIAL"

# ── Step 1: 让 DIDI 变成悬浮窗 ────────────────────────────
step "1/4" "检查/切换到悬浮窗"

DIDI_FOCUS=$($ADB shell "dumpsys window 2>/dev/null | grep mCurrentFocus" | head -1)
DIDI_TASK_MODE=$($ADB shell "dumpsys activity activities 2>/dev/null | grep -A1 'com.sdu.didi.gsui' | grep mode=" | head -1)

if echo "$DIDI_TASK_MODE" | grep -q "freeform"; then
    ok "DIDI 已在悬浮窗模式"
elif echo "$DIDI_FOCUS" | grep -q "com.sdu.didi.gsui"; then
    warn "DIDI 全屏 → 发送 HOME 最小化"
    $ADB shell input keyevent HOME
    sleep 1

    # 重新拉回并设为 freeform (华为特有: 从最近任务里点悬浮窗图标)
    # 1) 打开最近任务
    $ADB shell input keyevent APP_SWITCH
    sleep 1

    # 2) 点 DIDI 卡片的悬浮窗图标 (Huawei 最近任务页每个卡片右上角有图标)
    #    坐标需要 dump 动态取
    $ADB shell uiautomator dump /sdcard/u.xml 2>/dev/null
    $ADB pull /sdcard/u.xml /tmp/recent.xml >/dev/null 2>&1
    $ADB shell rm /sdcard/u.xml 2>/dev/null

    # 找 DIDI 卡片的悬浮窗按钮(通常 content-desc="浮窗" 或 text="浮窗")
    FLOAT_BTN=$(python3 -c "
import re
xml = open('/tmp/recent.xml').read()
for m in re.finditer(r'<node\b[^>]*?>', xml):
    s = m.group(0)
    text = re.search(r'\btext=\"([^\"]*)\"', s)
    desc = re.search(r'\bcontent-desc=\"([^\"]+)\"', s)
    label = (text.group(1) if text else '') or (desc.group(1) if desc else '')
    if '浮窗' in label or '悬浮' in label or 'freeform' in label.lower():
        b = re.search(r'\bbounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', s)
        if b:
            x1,y1,x2,y2 = map(int,b.groups())
            print(f'{(x1+x2)//2} {(y1+y2)//2}')
            break
")
    if [ -n "$FLOAT_BTN" ]; then
        $ADB shell input tap $FLOAT_BTN
        sleep 1
        ok "DIDI 已切换为悬浮窗"
    else
        # fallback: 直接点 DIDI 卡片(回到全屏),后续安装时按 HOME
        warn "未找到悬浮窗按钮, 回 HOME 最小化"
        $ADB shell input keyevent HOME
    fi
else
    ok "DIDI 不在前台,无需切换"
fi

# ── Step 2: 推送安装 ──────────────────────────────────────
step "2/4" "推送 APK 安装"

# 先确认文件存在
[ -f "$APK" ] || { err "APK 不存在: $APK"; exit 1; }
ls -lh "$APK"

# 后台安装(install 命令会阻塞等华为弹窗处理)
$ADB install -r -t -d "$APK" 2>&1 &
INSTALL_PID=$!

# ── Step 3: 处理华为弹窗 ────────────────────────────────────
step "3/4" "处理安装弹窗"

# 等待弹窗出现
for i in $(seq 1 30); do
    sleep 0.3
    FOCUS=$($ADB shell "dumpsys window 2>/dev/null | grep mCurrentFocus" | head -1)
    if echo "$FOCUS" | grep -qE "packageinstaller|InstallStaging|PackageInstaller"; then
        echo "  弹窗出现: $FOCUS"
        break
    fi
    # 检查是否已安装完成
    if ! kill -0 $INSTALL_PID 2>/dev/null; then
        ok "install 进程已退出(无需弹窗 or 秒装完)"
        break
    fi
done

# 弹窗阶段 1: 风险提示 → 点"继续安装"
sleep 1
$ADB shell uiautomator dump /sdcard/u.xml 2>/dev/null
$ADB pull /sdcard/u.xml /tmp/install1.xml >/dev/null 2>&1
$ADB shell rm /sdcard/u.xml 2>/dev/null

CONTINUE_BTN=$(python3 -c "
import re
xml = open('/tmp/install1.xml').read()
for m in re.finditer(r'<node\b[^>]*?>', xml):
    s = m.group(0)
    text = re.search(r'\btext=\"继续安装\"', s)
    if text and 'clickable=\"true\"' in s:
        b = re.search(r'\bbounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', s)
        x1,y1,x2,y2 = map(int,b.groups())
        print(f'{(x1+x2)//2} {(y1+y2)//2}')
        break
")
if [ -n "$CONTINUE_BTN" ]; then
    echo "  点'继续安装' @ $CONTINUE_BTN"
    $ADB shell input tap $CONTINUE_BTN
    sleep 2
else
    warn "未找到'继续安装'按钮(可能已自动跳过)"
fi

# 弹窗阶段 2: 复选框 + 继续安装
$ADB shell uiautomator dump /sdcard/u.xml 2>/dev/null
$ADB pull /sdcard/u.xml /tmp/install2.xml >/dev/null 2>&1
$ADB shell rm /sdcard/u.xml 2>/dev/null

# 2a: 找复选框(class=android.widget.CheckBox)
CHECKBOX=$(python3 -c "
import re
xml = open('/tmp/install2.xml').read()
for m in re.finditer(r'<node\b[^>]*?>', xml):
    s = m.group(0)
    if 'CheckBox' not in s: continue
    b = re.search(r'\bbounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', s)
    if b:
        x1,y1,x2,y2 = map(int,b.groups())
        print(f'{(x1+x2)//2} {(y1+y2)//2}')
        break
")
if [ -n "$CHECKBOX" ]; then
    echo "  勾选复选框 @ $CHECKBOX"
    $ADB shell input tap $CHECKBOX
    sleep 1
fi

# 2b: 再次找"继续安装"按钮
CONTINUE2=$(python3 -c "
import re
xml = open('/tmp/install2.xml').read()
for m in re.finditer(r'<node\b[^>]*?>', xml):
    s = m.group(0)
    text = re.search(r'\btext=\"继续安装\"', s)
    if text and 'clickable=\"true\"' in s:
        b = re.search(r'\bbounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', s)
        x1,y1,x2,y2 = map(int,b.groups())
        print(f'{(x1+x2)//2} {(y1+y2)//2}')
        break
")
if [ -n "$CONTINUE2" ]; then
    echo "  点'继续安装'(第2次) @ $CONTINUE2"
    $ADB shell input tap $CONTINUE2
    sleep 2
fi

# 弹窗阶段 3: 锁屏密码
$ADB shell uiautomator dump /sdcard/u.xml 2>/dev/null
$ADB pull /sdcard/u.xml /tmp/install3.xml >/dev/null 2>&1
$ADB shell rm /sdcard/u.xml 2>/dev/null

PASSWD_FIELD=$(python3 -c "
import re
xml = open('/tmp/install3.xml').read()
for m in re.finditer(r'<node\b[^>]*?>', xml):
    s = m.group(0)
    cls = re.search(r'\bclass=\"([^\"]+)\"', s)
    rid = re.search(r'\bresource-id=\"([^\"]+)\"', s)
    if cls and ('EditText' in cls.group(1) or 'edit' in cls.group(1).lower()) or \
       rid and ('password' in rid.group(1).lower() or 'pin' in rid.group(1).lower()):
        b = re.search(r'\bbounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', s)
        x1,y1,x2,y2 = map(int,b.groups())
        print(f'{(x1+x2)//2} {(y1+y2)//2}')
        break
")
if [ -n "$PASSWD_FIELD" ]; then
    echo "  密码输入框 @ $PASSWD_FIELD"
    if [ -z "$PIN" ]; then
        read -s -p "  输入锁屏密码: " PIN
        echo ""
    fi
    $ADB shell input tap $PASSWD_FIELD
    sleep 0.5
    $ADB shell input text "$PIN"
    sleep 0.5
    # 点确认按钮(通常在键盘右下)
    $ADB shell input keyevent KEYCODE_ENTER
    sleep 2
else
    warn "未找到密码输入框(可能无需密码)"
fi

# ── Step 4: 验证 ──────────────────────────────────────────
step "4/4" "验证安装"

# 等 install 进程退出
wait $INSTALL_PID 2>/dev/null || true

LAST=$($ADB shell "dumpsys package com.didi.voiceblocker 2>/dev/null | grep lastUpdateTime" | tr -d '\r')
echo "  $LAST"
echo ""
ok "安装完成 - APK: $APK"
