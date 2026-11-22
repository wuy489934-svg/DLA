#!/data/data/com.termux/files/usr/bin/bash
# DLA Termux 一键环境：只装通用能力（shell/Node/Python/git/rg），不安装额外 Agent 运行时。
set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
SRC="$PREFIX/etc/apt/sources.list"
BACKUP="$SRC.dla-backup"

echo "DLA_TERMUX_SETUP_BEGIN"
if [ -f "$SRC" ] && [ ! -f "$BACKUP" ]; then
  cp "$SRC" "$BACKUP"
fi

set_mirror() {
  local url="$1"
  if command -v curl >/dev/null 2>&1; then
    curl -fsI --max-time 8 "$url/dists/stable/Release" >/dev/null 2>&1 || return 1
  fi
  echo "deb $url stable main" > "$SRC"
  return 0
}

# 清华 -> 中科大 -> 官方源
if ! set_mirror "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"; then
  if ! set_mirror "https://mirrors.ustc.edu.cn/termux/apt/termux-main"; then
    if [ -f "$BACKUP" ]; then cp "$BACKUP" "$SRC"; fi
  fi
fi

if ! pkg update -y; then
  if [ -f "$BACKUP" ]; then cp "$BACKUP" "$SRC"; fi
  pkg update -y
fi

# 只装通用工具链，不装额外 Agent 运行时
pkg install -y curl git nodejs python ripgrep

echo "NODE_VERSION=$(node -v 2>/dev/null || echo none)"
echo "NPM_VERSION=$(npm -v 2>/dev/null || echo none)"
echo "PYTHON_VERSION=$(python --version 2>&1 || echo none)"
echo "RG_VERSION=$(rg --version 2>/dev/null | head -1 || echo none)"

# 国内包索引
npm config set registry https://registry.npmmirror.com
mkdir -p "$HOME/.pip"
printf '[global]\nindex-url = https://pypi.tuna.tsinghua.edu.cn/simple\n' > "$HOME/.pip/pip.conf"

echo "TERMUX_PREFIX=$PREFIX"
echo "DLA_TERMUX_SETUP_DONE"
