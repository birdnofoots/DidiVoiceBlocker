#!/bin/bash
# git_push.sh — 推送到 GitHub(先检测网络,不通提示开梯子)
set -e

echo "=== 检测 GitHub 连通性 ==="
if curl -s --connect-timeout 5 -o /dev/null -w "%{http_code}" https://github.com 2>/dev/null | grep -q 200; then
    echo "✅ GitHub 可达"
else
    echo "❌ GitHub 不可达,请打开梯子后重试"
    exit 1
fi

echo "=== git push ==="
git push origin main "$@"
echo "✅ 推送完成"
