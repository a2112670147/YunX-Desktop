#!/usr/bin/env bash
# YunX-Desktop AppImage 打包脚本
# 输出: release/YunX-Desktop-<version>-<arch>.AppImage（单文件，chmod +x 后可直接运行）
#
# 为什么要单独一个脚本：
#   compose-gradle-plugin 1.7.3 的 TargetFormat.AppImage 只会调用
#   `jpackage --type app-image`，产物是一个目录（bin/ + lib/），不会生成 .AppImage。
#   这里复用 createDistributable 的 app-image，用 appimagetool 封装成真正的单文件。
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"

APP_NAME="YunX-Desktop"
app_version="$(tr -d '[:space:]' < version.txt)"
case "$(uname -m)" in
  x86_64 | amd64) arch="x86_64" ;;
  aarch64 | arm64) arch="aarch64" ;;
  *) echo "[appimage] 不支持的架构: $(uname -m)" >&2; exit 1 ;;
esac

dist="$root/desktop/build/compose/binaries/main/app/$APP_NAME"
appdir="$root/desktop/build/appimage/AppDir"
out_dir="$root/release"
tool_dir="$root/tools/appimagetool"
tool_bin="$tool_dir/squashfs-root/usr/bin/appimagetool"

log() { printf '\033[36m[appimage]\033[0m %s\n' "$*"; }
die() {
  printf '\033[31m[appimage]\033[0m %s\n' "$*" >&2
  exit 1
}

# ---- 1. 用 Gradle 产出 jpackage app-image ----
# 已构建过时 createDistributable 会走 up-to-date，秒回。
# 由 Gradle 任务 buildAppImage 调用本脚本时跳过，避免嵌套启动 Gradle 卡死 daemon。
if [ "${YUNX_SKIP_GRADLE:-0}" != "1" ]; then
  log "构建 app-image（jpackage）..."
  ./gradlew :desktop:createDistributable --console=plain
fi
[ -x "$dist/bin/$APP_NAME" ] || die "未找到 app-image 可执行文件: $dist/bin/$APP_NAME（先跑 ./gradlew :desktop:createDistributable）"

# ---- 2. 准备 appimagetool（自带 mksquashfs，无需系统装 squashfs-tools）----
if [ ! -x "$tool_bin" ]; then
  log "下载 appimagetool ($arch)..."
  mkdir -p "$tool_dir"
  src_url="https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$arch.AppImage"
  # 国内直连 GitHub 常失败，失败后回退 ghfast 镜像
  ok=0
  for url in "${APPIMAGETOOL_URL:-$src_url}" "https://ghfast.top/$src_url"; do
    if curl -fL --connect-timeout 20 --retry 2 -o "$tool_dir/appimagetool.AppImage" "$url"; then
      ok=1
      break
    fi
    log "下载失败，尝试下一个源: $url"
  done
  [ "$ok" = 1 ] || die "appimagetool 下载失败，可手动放置到 $tool_dir/appimagetool.AppImage"
  chmod +x "$tool_dir/appimagetool.AppImage"
  # --appimage-extract 由 runtime 自身完成，不需要 FUSE
  (cd "$tool_dir" && ./appimagetool.AppImage --appimage-extract >/dev/null)
fi
[ -x "$tool_bin" ] || die "appimagetool 解压失败，缺少: $tool_bin"

# ---- 3. 组装 AppDir ----
log "组装 AppDir ..."
rm -rf "$appdir"
mkdir -p "$appdir"
# 注意：不用硬链接，构建目录与工程目录可能跨设备
cp -a "$dist/bin" "$dist/lib" "$appdir/"

# 图标：jpackage 已把 icon.png 转成 lib/<AppName>.png
if [ -f "$dist/lib/$APP_NAME.png" ]; then
  cp "$dist/lib/$APP_NAME.png" "$appdir/yunx-desktop.png"
else
  cp "$root/desktop/src/main/resources/icon.png" "$appdir/yunx-desktop.png"
fi

cat >"$appdir/AppRun" <<EOF
#!/bin/sh
HERE=\$(dirname "\$(readlink -f "\$0")")
exec "\$HERE/bin/$APP_NAME" "\$@"
EOF
chmod +x "$appdir/AppRun"

cat >"$appdir/yunx-desktop.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=YunX Desktop
Comment=Netdisk share-link parser and high-speed downloader
Exec=$APP_NAME
Icon=yunx-desktop
Categories=Network;FileTransfer;
Terminal=false
X-AppImage-Version=$app_version
EOF

# ---- 4. 打包 ----
# appimagetool 会在 PATH 里找 mksquashfs，解压目录里的那份够用
export PATH="$tool_dir/squashfs-root/usr/bin:$PATH"
mkdir -p "$out_dir"
target="$out_dir/$APP_NAME-$app_version-$arch.AppImage"
log "生成 $(basename "$target") ..."
ARCH="$arch" "$tool_bin" --no-appstream "$appdir" "$target"
chmod +x "$target"

log "完成: $target ($(du -h "$target" | cut -f1))"
