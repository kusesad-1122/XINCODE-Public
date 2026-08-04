#!/usr/bin/env bash
# 把 codegraph 内核交叉编译成 Android 动态库,产物直接落到 app 的 jniLibs。
#
# 只编 arm64-v8a:2019 年之后的 Android 设备基本都是 arm64,而每多一个 ABI
# APK 就多 34MB —— 那个代价换来的覆盖率提升太小了。真有 32 位设备的需求再加。
#
# 用法:codegraph-kernel/build-android.sh
# 需要:rustup + Android NDK(ANDROID_NDK_HOME 或常见路径)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/../app/src/main/jniLibs/arm64-v8a"

# 找 NDK。CI 上 setup-android 会设 ANDROID_NDK_HOME;本地按常见路径猜。
NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
  for c in "$ANDROID_HOME"/ndk/* /opt/android-sdk/ndk/* "$HOME"/Android/Sdk/ndk/*; do
    [ -d "$c" ] && NDK="$c" && break
  done
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "✗ 找不到 Android NDK。设置 ANDROID_NDK_HOME 后重试。" >&2
  exit 1
fi
echo "NDK: $NDK"

TC=""
for host in linux-x86_64 darwin-x86_64 windows-x86_64; do
  candidate="$NDK/toolchains/llvm/prebuilt/$host/bin"
  [ -d "$candidate" ] && TC="$candidate" && break
done
if [ -z "$TC" ]; then
  echo "✗ NDK LLVM toolchain not found under $NDK" >&2
  exit 1
fi

CLANG="$TC/aarch64-linux-android28-clang"
AR="$TC/llvm-ar"
if [ -f "$CLANG.cmd" ]; then
  CLANG="$CLANG.cmd"
  AR="$AR.exe"
fi

# minSdk 是 28,工具链要对上 —— 用高于 minSdk 的 API level 编出来的库
# 在低版本设备上会因为缺符号直接加载失败。
export CC_aarch64_linux_android="$CLANG"
export AR_aarch64_linux_android="$AR"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"

rustup target add aarch64-linux-android >/dev/null 2>&1 || true

cd "$HERE"
cargo build --release --target aarch64-linux-android --no-default-features

mkdir -p "$OUT"
cp target/aarch64-linux-android/release/libcodegraph_kernel.so "$OUT/"
echo "✓ $(du -h "$OUT/libcodegraph_kernel.so" | cut -f1) → $OUT/libcodegraph_kernel.so"
