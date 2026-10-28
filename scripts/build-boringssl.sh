#!/usr/bin/env bash
#
# Build BoringSSL (libssl + libcrypto) as static libraries for kron, with the Kotlin/Native toolchain's own clang
# and sysroot (glibc 2.19), so they link into a kron binary as they are. Output, committed like ioxide's .so files:
# native/linux-x64/libssl.a, native/linux-x64/libcrypto.a, and the headers they were built from in
# native/include/openssl.
#
#   scripts/build-boringssl.sh                   # build with the pinned ref below
#   BORINGSSL_REF=<commit> scripts/build-boringssl.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

# PINNED to the commit ioxide's libioxide_boringssl.so is built from (2026-10-11): one BoringSSL across the runtimes.
BORINGSSL_REF=${BORINGSSL_REF:-5b83e5d1abf0ae0033ae52ab579d906b67633b2c}
WORK=${WORK:-/tmp/kron-boringssl}
OUT=native/linux-x64
HEADERS=native/include

# The toolchain Gradle downloaded for Kotlin/Native: its clang, and the gcc 8.3 / glibc 2.19 sysroot it links against.
KONAN=${KONAN_DATA_DIR:-$HOME/.konan}/dependencies
LLVM=$(ls -d "$KONAN"/llvm-*-x86_64-linux-essentials-* | tail -1)/bin
GCC=$(ls -d "$KONAN"/x86_64-unknown-linux-gnu-gcc-*-glibc-* | tail -1)
[ -x "$LLVM/clang" ] && [ -d "$GCC" ] || {
    echo "no Kotlin/Native toolchain under $KONAN: build kron once with Gradle first" >&2
    exit 1
}
TARGET_FLAGS="--target=x86_64-unknown-linux-gnu --gcc-toolchain=$GCC --sysroot=$GCC/x86_64-unknown-linux-gnu/sysroot"

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
cmake -S "$WORK/boringssl" -B "$WORK/build" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER="$LLVM/clang" -DCMAKE_CXX_COMPILER="$LLVM/clang++" \
    -DCMAKE_C_FLAGS="$TARGET_FLAGS" -DCMAKE_CXX_FLAGS="$TARGET_FLAGS" \
    -DCMAKE_EXE_LINKER_FLAGS="-fuse-ld=lld" -DCMAKE_SHARED_LINKER_FLAGS="-fuse-ld=lld" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON >"$WORK/cmake.log"
cmake --build "$WORK/build" --target ssl crypto -j"$(nproc)" >"$WORK/build.log"

echo "==> installing into $OUT and $HEADERS"
mkdir -p "$OUT" "$HEADERS"
for lib in libssl.a libcrypto.a; do
    cp "$WORK/build/$lib" "$OUT/$lib"
    strip --strip-debug "$OUT/$lib"
done
rm -rf "$HEADERS/openssl"
cp -r "$WORK/boringssl/include/openssl" "$HEADERS/openssl"
echo "$BORINGSSL_REF" >"$OUT/BORINGSSL_REF"

echo "==> done: $(du -h "$OUT/libssl.a" | cut -f1) libssl.a, $(du -h "$OUT/libcrypto.a" | cut -f1) libcrypto.a"
