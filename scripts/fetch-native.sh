#!/usr/bin/env bash
# 下载内置运行时的预编译二进制到 android/app/src/main/jniLibs/arm64-v8a/。
#
# 来源：DSH-Android 的原生预编译发布（Apache-2.0；Node 自身是 MIT），
# 包含 libnode.so（Node 24）、libdshnode.so（node 启动器）、librg.so（ripgrep）、
# libc++_shared.so（NDK 运行时）。这些文件不进仓库，构建前跑一次本脚本。
#
# 可用环境变量覆盖：DLA_NATIVE_REPO、DLA_NATIVE_TAG。
set -euo pipefail

TAG="${DLA_NATIVE_TAG:-native-node24.22.1-c5b68634a6}"
REPO="${DLA_NATIVE_REPO:-jackwener/DSH-Android}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "fetching $REPO@$TAG ..."
curl -fsSL --retry 3 "https://github.com/$REPO/releases/download/$TAG/$TAG.tar.gz" -o "$TMP/native.tar.gz"
tar -xzf "$TMP/native.tar.gz" -C "$TMP"

mkdir -p "$DEST"
for name in libnode.so libdshnode.so libc++_shared.so librg.so; do
  cp "$TMP/arm64-v8a/$name" "$DEST/$name"
done

echo "native libs -> $DEST"
ls -l "$DEST"
