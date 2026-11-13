#!/usr/bin/env bash
# 构建 release 包，并把 APK 与 latest.json 上传到站点目录。
# 用法：
#   ./upload-release.sh              # 构建 + 上传（并删除服务器上其它版本的 APK）
#   ./upload-release.sh --skip-build # 只上传已有产物
set -euo pipefail

SERVER="${IPTV_DEPLOY_TARGET:-root@bastion}"
REMOTE_DIR="${IPTV_DEPLOY_DIR:-/usr/share/nginx/default/static/iptv}"
PUBLIC_BASE="${IPTV_PUBLIC_BASE:-https://www.hdfk7.cn/static/iptv}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

SKIP_BUILD=0
for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --prune) ;;
        *) echo "未知参数: $arg" >&2; exit 1 ;;
    esac
done

if [ "$SKIP_BUILD" -eq 0 ]; then
    echo "==> 清理构建产物"
    ./gradlew clean --console=plain
    echo "==> 构建 release"
    ./gradlew :app:assembleRelease --console=plain
fi

APK="$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1 || true)"
MANIFEST="app/build/outputs/update/release/latest.json"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
    echo "没找到 release APK，先执行 ./gradlew :app:assembleRelease" >&2
    exit 1
fi
if [ ! -f "$MANIFEST" ]; then
    echo "没找到更新清单: $MANIFEST" >&2
    exit 1
fi

APK_NAME="$(basename "$APK")"
MANIFEST_APK_URL="$(grep -o '"apkUrl"[[:space:]]*:[[:space:]]*"[^"]*"' "$MANIFEST" | sed -E 's/.*"([^"]*)"$/\1/')"
VERSION_CODE="$(grep -o '"versionCode"[[:space:]]*:[[:space:]]*[0-9]*' "$MANIFEST" | grep -o '[0-9]*$')"
VERSION_NAME="$(grep -o '"versionName"[[:space:]]*:[[:space:]]*"[^"]*"' "$MANIFEST" | sed -E 's/.*"([^"]*)"$/\1/')"

if [ "$MANIFEST_APK_URL" != "$APK_NAME" ]; then
    echo "清单里的 apkUrl($MANIFEST_APK_URL) 和 APK 文件名($APK_NAME) 对不上，停止上传" >&2
    exit 1
fi

echo "==> 版本: versionCode=$VERSION_CODE versionName=$VERSION_NAME"
echo "==> 目标: $SERVER:$REMOTE_DIR"

echo "==> 创建上传目录"
ssh -o BatchMode=yes "$SERVER" "mkdir -p -- '$REMOTE_DIR'"

STAGE="$(ssh -o BatchMode=yes "$SERVER" "cd '$REMOTE_DIR' && mktemp -d .release-upload.XXXXXXXX")"
STAGED_APK="$STAGE/$APK_NAME"
STAGED_MANIFEST="$STAGE/latest.json"
scp -o BatchMode=yes "$APK" "$SERVER:$REMOTE_DIR/$STAGED_APK"
scp -o BatchMode=yes "$MANIFEST" "$SERVER:$REMOTE_DIR/$STAGED_MANIFEST"
ssh -o BatchMode=yes "$SERVER" "cd '$REMOTE_DIR' && (timeout 60 flock 9 && test -s '$STAGED_APK' && test -s '$STAGED_MANIFEST' && mv -f -- '$STAGED_APK' '$APK_NAME' && mv -f -- '$STAGED_MANIFEST' latest.json && rmdir -- '$STAGE' && find . -maxdepth 1 -type f -name 'IPTV-release-*.apk' -not -name '$APK_NAME' -delete && ls -lh -- '$APK_NAME' latest.json) 9>.release.lock"

echo "==> 完成"
echo "    $PUBLIC_BASE/$APK_NAME"
echo "    $PUBLIC_BASE/latest.json"
