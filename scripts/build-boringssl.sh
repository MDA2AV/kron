#!/usr/bin/env bash
#
# Build BoringSSL (libssl + libcrypto) as static libraries for kron, with the Kotlin/Native toolchain's own clang
# and sysroot (glibc 2.19 on x64, 2.25 on arm64), so they link into a kron binary as they are. Output, committed like
# ioxide's .so files: native/linux-<arch>/libssl.a, native/linux-<arch>/libcrypto.a, and the headers they were built
# from in native/include/openssl, the same for both.
#
#   scripts/build-boringssl.sh                   # linux-x64, with the pinned ref below
#   scripts/build-boringssl.sh arm64             # linux-arm64, cross-compiled from x64 by the same clang
#   BORINGSSL_REF=<commit> scripts/build-boringssl.sh [x64|arm64]
#
set -euo pipefail
cd "$(dirname "$0")/.."

# PINNED to the commit ioxide's libioxide_boringssl.so is built from (2026-10-11): one BoringSSL across the runtimes.
BORINGSSL_REF=${BORINGSSL_REF:-5b83e5d1abf0ae0033ae52ab579d906b67633b2c}
ARCH=${1:-x64}
case "$ARCH" in
    x64)
        TRIPLE=x86_64-unknown-linux-gnu
        CROSS=()
        ;;
    arm64)
        TRIPLE=aarch64-unknown-linux-gnu
        CROSS=(-DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64)
        ;;
    *)
        echo "unknown architecture '$ARCH': x64 or arm64" >&2
        exit 1
        ;;
esac

WORK=${WORK:-/tmp/kron-boringssl-$ARCH}
OUT=native/linux-$ARCH
HEADERS=native/include

# The toolchain Gradle downloaded for Kotlin/Native: its clang, and the gcc 8.3 sysroot of the target it links against.
KONAN=${KONAN_DATA_DIR:-$HOME/.konan}/dependencies
LLVM=$(ls -d "$KONAN"/llvm-*-x86_64-linux-essentials-* | tail -1)/bin
GCC=$(ls -d "$KONAN"/$TRIPLE-gcc-*-glibc-* 2>/dev/null | tail -1)
[ -x "$LLVM/clang" ] && [ -d "$GCC" ] || {
    echo "no Kotlin/Native toolchain for $TRIPLE under $KONAN: build kron for it once with Gradle first" >&2
    exit 1
}
TARGET_FLAGS="--target=$TRIPLE --gcc-toolchain=$GCC --sysroot=$GCC/$TRIPLE/sysroot"

rm -rf "$WORK" && mkdir -p "$WORK"

echo "==> fetching boringssl ($BORINGSSL_REF)"
git -C "$WORK" init -q boringssl
git -C "$WORK/boringssl" remote add origin https://github.com/google/boringssl
git -C "$WORK/boringssl" fetch -q --depth 1 origin "$BORINGSSL_REF" || {
    echo "could not fetch $BORINGSSL_REF" >&2
    exit 1
}
git -C "$WORK/boringssl" checkout -q FETCH_HEAD

echo "==> building libssl + libcrypto (static, PIC) with $LLVM/clang"
# The target on the assembly too (CMake assembles .S files with the ASM flags alone). No debug info (-g0 in the release
# flags, which come after BoringSSL's -ggdb), and archives made and indexed by the toolchain's llvm-ar: the host's
# binutils may not read the target's objects.
cmake -S "$WORK/boringssl" -B "$WORK/build" -DCMAKE_BUILD_TYPE=Release "${CROSS[@]}" \
    -DCMAKE_C_COMPILER="$LLVM/clang" -DCMAKE_CXX_COMPILER="$LLVM/clang++" \
    -DCMAKE_C_FLAGS="$TARGET_FLAGS" -DCMAKE_CXX_FLAGS="$TARGET_FLAGS" -DCMAKE_ASM_FLAGS="$TARGET_FLAGS" \
    -DCMAKE_C_FLAGS_RELEASE="-O3 -DNDEBUG -g0" -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG -g0" \
    -DCMAKE_AR="$LLVM/llvm-ar" \
    -DCMAKE_C_ARCHIVE_FINISH="<CMAKE_AR> s <TARGET>" -DCMAKE_CXX_ARCHIVE_FINISH="<CMAKE_AR> s <TARGET>" \
    -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
    -DCMAKE_EXE_LINKER_FLAGS="-fuse-ld=lld" -DCMAKE_SHARED_LINKER_FLAGS="-fuse-ld=lld" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON >"$WORK/cmake.log"
cmake --build "$WORK/build" --target ssl crypto -j"$(nproc)" >"$WORK/build.log"

echo "==> installing into $OUT and $HEADERS"
mkdir -p "$OUT" "$HEADERS"
for lib in libssl.a libcrypto.a; do
    cp "$WORK/build/$lib" "$OUT/$lib"
done
rm -rf "$HEADERS/openssl"
cp -r "$WORK/boringssl/include/openssl" "$HEADERS/openssl"
echo "$BORINGSSL_REF" >"$OUT/BORINGSSL_REF"

echo "==> done: $(du -h "$OUT/libssl.a" | cut -f1) libssl.a, $(du -h "$OUT/libcrypto.a" | cut -f1) libcrypto.a"
