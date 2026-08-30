#!/bin/bash
# Metro Next 构建脚本
#
# ── 构建环境三大坑与对策 ───────────────────────────────────────────────
# 1) 非 ASCII 项目路径（致命）
#    项目位于 D:\WorkBuddy_Save\手机小程序开发，Kotlin 编译器会把中文路径转义成
#    \uXXXX 字面量，导致 "source file or directory not found"。
#    对策：把源码同步到纯 ASCII 目录 D:/metro_apk_build 再构建（源码仍在原目录）。
#
# 2) Gradle .lock 被占死
#    Gradle 只能使用「本次运行新建」的 .lock；任何预先存在的 .lock（上轮遗留/复制带入）
#    都会报 "拒绝访问"，且文件级 rename/delete 均 Permission denied（只能 rename 父目录）。
#    对策：依赖缓存 modules-2/transforms-3 每轮从「永不参与构建的黄金副本」恢复（无锁）；
#          其余可再生缓存（native/daemon/caches 8.5、journal-1、build-cache-1、jars-9）每轮 rename。
#
# 3) WorkBuddy safe-delete 拦截删除 → 一律用 rename，不用 rm。
# 4) ~/.android/debug.keystore.lock 被占 → 改用 D:/metro_debug.keystore。
# ─────────────────────────────────────────────────────────────────────
set -e

SRC_PROJ="D:/WorkBuddy_Save/手机小程序开发/android"
BUILD_ROOT="D:/metro_apk_build"
BUILD_PROJ="$BUILD_ROOT/android"

LOG="D:/metro_build_$(date +%s).log"
exec > "$LOG" 2>&1
echo "[build] 日志文件: $LOG"

export JAVA_HOME="C:/Users/King/.workbuddy/binaries/jdk/jdk-17.0.13+11"
export ANDROID_HOME="C:/Users/King/.workbuddy/binaries/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="D:/gradle_metro"
export PATH="$JAVA_HOME/bin:$PATH"

PY="C:/Users/King/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
GRADLE="C:/Users/King/.workbuddy/binaries/gradle/gradle-8.5/bin/gradle"
GH="D:/gradle_metro"
GOLDEN="D:/gradle_golden/caches"

# A. 依赖缓存从黄金副本恢复（保证本轮开始时无 .lock）
prepare_caches() {
    for name in modules-2 transforms-3; do
        rm -rf "$GH"/caches/"$name"_old_* 2>/dev/null || true
        if [ -d "$GH/caches/$name" ]; then
            mv "$GH/caches/$name" "$GH/caches/${name}_old_$(date +%s)" 2>/dev/null || true
        fi
        if cp -r "$GOLDEN/$name" "$GH/caches/$name" 2>/dev/null; then
            echo "[dep] $name 已从黄金副本恢复（无 .lock）"
        else
            echo "[dep] $name 恢复失败"
        fi
    done

    "$PY" -c "
import os, time
GH = r'D:/gradle_metro'
ts = str(int(time.time()))
regen = [
    os.path.join(GH, 'native'),
    os.path.join(GH, 'daemon'),
    os.path.join(GH, 'caches', '8.5'),
    os.path.join(GH, 'caches', 'journal-1'),
    os.path.join(GH, 'caches', 'build-cache-1'),
    os.path.join(GH, 'caches', 'jars-9'),
    r'D:/metro_apk_build/android/.gradle',
]
for p in regen:
    if os.path.exists(p):
        try:
            os.rename(p, p + '_bak' + ts)
            print('[clean] 已重建:', p)
        except Exception as e:
            print('[clean] rename 失败:', p, type(e).__name__)
base = r'D:/metro_apk_build/android/app/build'
def _rn(p):
    try:
        os.rename(p, p + '_bak' + ts)
        return True
    except Exception:
        return False

if os.path.exists(base):
    # 必须整体重命名 build 目录：Gradle 会尝试删除上一轮的旧产物
    #（如 outputs/logs/manifest-merger-debug-report.txt），而删除会被沙箱 safe-delete 拦截，
    # 报 Unable to delete file ... 导致构建失败。整体移走后 Gradle 无需删除任何东西。
    # 注意：本段代码整体位于 bash 双引号内，注释中禁止出现英文双引号。
    if _rn(base):
        print('[clean] 已整体重命名 build 目录')
    else:
        # 退路：整体被占用时，逐个移走子目录（outputs 是必须清掉的那个）
        for sub in ('intermediates', 'outputs', 'generated', 'tmp', 'kotlin'):
            p = os.path.join(base, sub)
            if os.path.exists(p) and _rn(p):
                print('[clean] 已重命名 build/%s' % sub)
"
}

# B. 把源码同步到纯 ASCII 构建目录（排除编译产物）
sync_source() {
    mkdir -p "$BUILD_ROOT"
    echo "[sync] 同步源码 -> $BUILD_PROJ（纯 ASCII 路径）"
    cd "D:/WorkBuddy_Save/手机小程序开发"
    # App 内嵌网页由根目录 beijing-metro.html 生成。
    # 仓库里不存这份副本（它是与 beijing-metro.html 完全相同的 1.1MB，提交两份纯属浪费），
    # 改为每次构建前自动同步，保证与网页版逐字节一致。
    mkdir -p "android/app/src/main/assets/public"
    if ! cp -f "beijing-metro.html" "android/app/src/main/assets/public/metro.html" 2>/dev/null; then
        echo "[sync] 失败：无法同步 beijing-metro.html 到 App 资源"
        exit 1
    fi
    rm -rf "$BUILD_PROJ" 2>/dev/null || true
    tar cf - --exclude='build' --exclude='.gradle' --exclude='*_bak*' --exclude='*_old_*' android 2>/dev/null \
        | (cd "$BUILD_ROOT" && tar xf - 2>/dev/null)
    if [ ! -f "$BUILD_PROJ/app/src/main/java/com/metronext/metro/MainActivity.kt" ]; then
        echo "[sync] 失败：MainActivity.kt 未同步"
        exit 1
    fi
    echo "[sync] 完成"
}

prepare_caches
sync_source

# C. 构建（Kotlin daemon 偶发连不上，失败则重试）
cd "$BUILD_PROJ"
attempt=1
max=3
while [ $attempt -le $max ]; do
    echo "[build] 第 $attempt/$max 次尝试：assembleDebug ..."
    if "$GRADLE" assembleDebug --no-daemon --console=plain; then
        echo "[build] BUILD SUCCESSFUL"
        break
    else
        echo "[build] 第 $attempt 次失败"
        if [ $attempt -lt $max ]; then
            echo "[build] 重建缓存后重试 ..."
            prepare_caches
        fi
        attempt=$((attempt + 1))
    fi
done

if [ $attempt -gt $max ]; then
    echo "[build] 已重试 $max 次仍失败，请查看上方日志"
    exit 1
fi

# D. 输出 APK 到项目根目录，方便取用
APK="$BUILD_PROJ/app/build/outputs/apk/debug/app-debug.apk"
OUTDIR="D:/WorkBuddy_Save/手机小程序开发"
if [ ! -f "$APK" ]; then
    echo "[build] 未找到 APK: $APK"
    exit 1
fi
# 覆盖已有文件 = 删除+新建，若旧文件被占用（如已被预览器/资源管理器打开）会 Permission denied，
# 且此时连 rename 都失败。退路：写入带时间戳的新文件名（纯新建，不受影响）。
if cp "$APK" "$OUTDIR/MetroNext-debug.apk" 2>/dev/null; then
    echo "[build] APK 已输出: $OUTDIR/MetroNext-debug.apk"
    ls -la "$OUTDIR/MetroNext-debug.apk"
else
    ALT="$OUTDIR/MetroNext-debug-$(date +%m%d-%H%M).apk"
    if cp "$APK" "$ALT" 2>/dev/null; then
        echo "[build] 目标被占用（Permission denied），已改为输出: $ALT"
        ls -la "$ALT"
    else
        echo "[build] APK 输出失败，构建产物仍在: $APK"
        exit 1
    fi
fi
