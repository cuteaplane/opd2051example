#!/usr/bin/env bash
# 用同一个 timer.c 生成 3 个变体，每个把 KSU 管理器包名写死进去。
#
#   NDK=$HOME/android/ndk-r28c ./tools/build-timer-variants.sh [timer.c] [assets_dir]
#
# 产物：app/src/main/assets/timer_{ksu,sukisu,bakasu}
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${NDK:-$HOME/android/ndk-r28c}"
SRC="${1:-$ROOT/../zjns-98260/timer/timer.c}"
OUT="${2:-$ROOT/app/src/main/assets}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

[ -x "$CC" ] || { echo "clang not found: $CC" >&2; echo "set NDK=/path/to/ndk" >&2; exit 1; }
[ -f "$SRC" ] || { echo "timer.c not found: $SRC" >&2; exit 1; }
mkdir -p "$OUT"

build() {
    local name="$1" pkg="$2"
    sed "s/com\.resukisu\.resukisu/$pkg/g" "$SRC" > "$TMP/timer_$name.c"
    "$CC" -O2 -std=gnu11 -static \
        -fno-stack-protector -fno-pic -I"$(dirname "$SRC")" \
        -ffunction-sections -fdata-sections \
        "$TMP/timer_$name.c" -o "$OUT/timer_$name" \
        -Wl,--gc-sections -Wl,-s -Wl,--build-id=none \
        -Wl,-z,norelro -pthread
    chmod 755 "$OUT/timer_$name"
    printf '%-10s %-24s %s\n' "$name" "$pkg" "$(sha256sum "$OUT/timer_$name" | cut -d' ' -f1)"
}

echo "src: $SRC"
echo "cc : $CC"
echo
build ksu     me.weishu.kernelsu
build sukisu  com.sukisu.ultra
if [ "${REBUILD_BAKASU:-0}" = "1" ] || [ ! -f "$OUT/timer_bakasu" ]; then
    build bakasu com.resukisu.resukisu
else
    echo "keep existing $OUT/timer_bakasu (已验证的 timer13；REBUILD_BAKASU=1 可强制重编)"
    printf '%-10s %-24s %s\n' bakasu com.resukisu.resukisu \
        "$(sha256sum "$OUT/timer_bakasu" | cut -d' ' -f1)"
fi
echo
file "$OUT"/timer_ksu "$OUT"/timer_sukisu "$OUT"/timer_bakasu
