#!/usr/bin/env bash
# 编译 native 管线（arm64-v8a + armeabi-v7a）。
#
# 为什么不用 Gradle 的 externalNativeBuild：那需要 SDK 里的 CMake，
# 这台机器上没装（SDK/cmake 为空）。直接用 NDK 自带的 clang 更省事，也更可控。
# 产物放进 app/src/main/jniLibs/，Gradle 打包时会自动带上。
set -e

NDK=${NDK:-/c/Users/wzy/AppData/Local/Android/Sdk/ndk/27.2.12479018}
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_ROOT="$(cd "$SRC_DIR/.." && pwd)/jniLibs"

CFLAGS="-O3 -std=c++17 -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections \
 -fno-exceptions -fno-rtti -Wall -Wno-unused-function"

build_abi() {
  local abi=$1 triple=$2 extra=$3
  local out="$OUT_ROOT/$abi"
  mkdir -p "$out"
  echo "== $abi =="
  "$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/${triple}28-clang++" \
    $CFLAGS $extra -shared -static-libstdc++ -Wl,--gc-sections -Wl,--no-undefined \
    -o "$out/libcamhdr.so" \
    "$SRC_DIR/hdr_jni.cpp" "$SRC_DIR/hdr_merge.cpp" \
    "$SRC_DIR/hdr_align.cpp" "$SRC_DIR/hdr_drc.cpp" \
    "$SRC_DIR/hdr_render.cpp" "$SRC_DIR/hdr_finish.cpp" "$SRC_DIR/hdr_superres.cpp" "$SRC_DIR/raw_defect.cpp" "$SRC_DIR/jpeg444.cpp" \
    "$SRC_DIR/heif_encode.cpp" "$SRC_DIR/png16_encode.cpp" -llog -lmediandk -lz
  ls -lh "$out/libcamhdr.so"
}

build_abi arm64-v8a aarch64-linux-android "-march=armv8-a+simd"
build_abi armeabi-v7a armv7a-linux-androideabi "-mfpu=neon -mfloat-abi=softfp -march=armv7-a"

# 产物校验：set -e 对"被管道包住"的调用不起作用（外层管道的退出码是 tail 的），
# 编译失败时旧 .so 会留在原地，Gradle 照样把它打进 APK——真的发生过一次。
# 所以这里自己查一遍：.so 必须比所有源文件新。
newest=$(ls -t "$SRC_DIR"/*.cpp "$SRC_DIR"/*.h | head -1)
for abi in arm64-v8a armeabi-v7a; do
  so="$OUT_ROOT/$abi/libcamhdr.so"
  [ -f "$so" ] || { echo "!! 缺少 $so，编译失败"; exit 1; }
  if [ ! "$so" -nt "$newest" ]; then
    echo "!! $so 比 $newest 旧，编译没有真正成功"; exit 1
  fi
done

echo "done"
