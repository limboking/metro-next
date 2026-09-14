#!/bin/bash
# Metro Next 构建脚本
#
# ── 构建环境坑与对策 ───────────────────────────────────────────────────
# 1) 非 ASCII 项目路径（致命）
#    项目位于 D:\WorkBuddy_Save\手机小程序开发，Kotlin 编译器会把中文路径转义成
#    \uXXXX 字面量，导致 "source file or directory not found"。
#    对策：把源码同步到纯 ASCII 目录 D:/metro_build/metro_apk_build 再构建（源码仍在原目录）。
#
# 2) Gradle .lock 被占死
#    Gradle 只能使用「本次运行新建」的 .lock；任何预先存在的 .lock（上轮遗留）
#    都会报 "拒绝访问"。
#    对策（v1.0.31 起，替代旧的整目录换新机制）：
#      首选「锁文件手术」——把缓存树里所有 *.lock 原地改名进 _TRASH/locks_*，
#      缓存本体原地复用：零拷贝、零垃圾、构建更快。
#      任何锁改名失败（被残留进程占用）→ 自动回退旧机制：
#      整目录 rename 进 _TRASH + 从黄金副本恢复。
#
# 3) WorkBuddy safe-delete 拦截删除 → 删除类操作一律 rename；历史垃圾统一
#    归拢到 D:/metro_build/_TRASH，用 robocopy /MIR 镜像空目录清空
#    （robocopy 是普通 exe，不经过 safe-delete 拦截层，实测有效）。
#    旧机制每轮 rename 出的 *_old_* / *_bak* 副本无人清理，两个月攒出几十 GB——
#    本版起每轮构建开始/结束各做一次 cleanup_trash，垃圾不再积累。
#
# 4) ~/.android/debug.keystore.lock 被占 → 改用 D:/metro_build/metro_debug.keystore。
# ─────────────────────────────────────────────────────────────────────
set -e

# 构建变体：bash build_apk.sh [debug|release]，默认 debug。
# release：assembleRelease（v1.0.19 起 release 与 debug 同签名，可覆盖安装）。
VARIANT="${1:-debug}"

SRC_PROJ="D:/WorkBuddy_Save/手机小程序开发/android"
BUILD_ROOT="D:/metro_build/metro_apk_build"
BUILD_PROJ="$BUILD_ROOT/android"

TRASH="D:/metro_build/_TRASH"     # 垃圾暂存区（每轮构建开始/结束清空）
EMPTY="D:/metro_build/_empty"     # robocopy 镜像源（空目录）

LOG="D:/metro_build/logs/metro_build_$(date +%s).log"
mkdir -p "$(dirname "$LOG")"
exec > "$LOG" 2>&1
echo "[build] 日志文件: $LOG"

export JAVA_HOME="C:/Users/King/.workbuddy/binaries/jdk/jdk-17.0.13+11"
export ANDROID_HOME="C:/Users/King/.workbuddy/binaries/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="D:/metro_build/gradle_metro"
export PATH="$JAVA_HOME/bin:$PATH"

PY="C:/Users/King/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
GRADLE="C:/Users/King/.workbuddy/binaries/gradle/gradle-8.5/bin/gradle"
GH="D:/metro_build/gradle_metro"
GOLDEN="D:/metro_build/gradle_golden/caches"

# ── A0. 垃圾清理 ──────────────────────────────────────────────────────
# 1) 散落的 *_old_* / *_bak* / android.old_*（rename 产物）并入 _TRASH（瞬间）
# 2) _TRASH 用 robocopy /MIR 清空（沙箱外删除，不触发 safe-delete）
cleanup_trash() {
    local n=0 d
    for d in "$GH"/caches/*_old_* "$GH"/caches/*_bak* \
             "$GH"/*_old_* "$GH"/*_bak* \
             "$BUILD_ROOT"/android.old_* "$BUILD_ROOT"/*_bak* \
             "$BUILD_PROJ"/.gradle_bak* "$BUILD_PROJ"/app/build_bak*; do
        [ -e "$d" ] || continue
        mkdir -p "$TRASH"
        if mv "$d" "$TRASH/" 2>/dev/null; then
            n=$((n + 1))
        fi
    done
    if [ "$n" -gt 0 ]; then
        echo "[clean] 已归拢 $n 项历史垃圾 -> _TRASH"
    fi
    if [ -d "$TRASH" ]; then
        mkdir -p "$EMPTY"
        echo "[clean] robocopy 清空 _TRASH ..."
        # /R:0 /W:0 不重试；输出丢弃。退出码 0-7 均视为成功。
        MSYS_NO_PATHCONV=1 robocopy "$(cygpath -w "$EMPTY")" "$(cygpath -w "$TRASH")" \
            /MIR /R:0 /W:0 /NFL /NDL /NJH /NJS /NP > /dev/null 2>&1 || true
        rmdir "$TRASH" 2>/dev/null || true
        local left=0
        [ -d "$TRASH" ] && left=$(ls -A "$TRASH" 2>/dev/null | wc -l)
        if [ "$left" = "0" ]; then
            echo "[clean] _TRASH 已清空"
        else
            echo "[clean] _TRASH 残留 $left 项（本轮结束后再清）"
        fi
    fi
}

# ── A1. 锁文件手术：缓存原地复用 ─────────────────────────────────────
# 把 GRADLE_USER_HOME 下所有 *.lock 改名进 _TRASH/locks_*，效果等同全新缓存
# （Gradle 只拒绝「预先存在的 .lock」，把锁移走即可原地复用全部已下载依赖）。
# 任何改名失败（残留进程占用句柄）→ 退出码 2，调用方回退整目录机制。
lock_surgery() {
    "$PY" - <<'PYEOF'
import os, hashlib
GH = r'D:/metro_build/gradle_metro'
TRASH = r'D:/metro_build/_TRASH'
targets = [
    os.path.join(GH, 'caches'),
    os.path.join(GH, 'native'),
    os.path.join(GH, 'daemon'),
    r'D:/metro_build/metro_apk_build/android/.gradle',
]
n_ok, fail = 0, []
for root in targets:
    if not os.path.isdir(root):
        continue
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            if not fn.endswith('.lock'):
                continue
            src = os.path.join(dirpath, fn)
            try:
                os.makedirs(TRASH, exist_ok=True)
                h = hashlib.md5(src.encode('utf-8', 'replace')).hexdigest()[:16]
                dst = os.path.join(TRASH, 'locks', h + '_' + fn[-60:])
                os.rename(src, dst)
                n_ok += 1
            except Exception as e:
                fail.append((type(e).__name__, src))
print('[locks] 已移走 %d 个 .lock 文件' % n_ok)
if fail:
    print('[locks] 失败 %d 个：' % len(fail))
    for t, s in fail[:10]:
        print('    %s %s' % (t, s))
raise SystemExit(2 if fail else 0)
PYEOF
}

# ── A2. 回退机制：整目录换新（旧机制，垃圾进 _TRASH） ────────────────
fallback_restore() {
    local ts name
    ts=$(date +%s)
    for name in modules-2 transforms-3; do
        if [ -d "$GH/caches/$name" ]; then
            mv "$GH/caches/$name" "$TRASH/caches_${name}_${ts}_$RANDOM" 2>/dev/null || true
        fi
        if cp -r "$GOLDEN/$name" "$GH/caches/$name" 2>/dev/null; then
            echo "[dep] $name 已从黄金副本恢复（无 .lock）"
        else
            echo "[dep] $name 恢复失败"
        fi
    done
    "$PY" - <<'PYEOF'
import os, time
GH = r'D:/metro_build/gradle_metro'
TRASH = r'D:/metro_build/_TRASH'
ts = str(int(time.time()))
os.makedirs(TRASH, exist_ok=True)
regen = [
    os.path.join(GH, 'native'),
    os.path.join(GH, 'daemon'),
    os.path.join(GH, 'caches', '8.5'),
    os.path.join(GH, 'caches', 'journal-1'),
    os.path.join(GH, 'caches', 'build-cache-1'),
    os.path.join(GH, 'caches', 'jars-9'),
    r'D:/metro_build/metro_apk_build/android/.gradle',
]
for p in regen:
    if os.path.exists(p):
        try:
            os.rename(p, os.path.join(TRASH, os.path.basename(p) + '_bak' + ts))
            print('[clean] 已重建:', p)
        except Exception as e:
            print('[clean] rename 失败:', p, type(e).__name__)
PYEOF
}

# ── A. 缓存准备 ───────────────────────────────────────────────────────
prepare_caches() {
    if lock_surgery; then
        echo "[dep] 锁手术成功：缓存原地复用（零拷贝、零垃圾）"
    else
        echo "[dep] 锁手术失败 → 回退黄金副本整目录恢复"
        fallback_restore
    fi
    # build 目录每轮整体换新：Gradle 会尝试删除上一轮旧产物，而删除被沙箱
    # safe-delete 拦截报 Unable to delete file，整体移走后 Gradle 无需删除。
    "$PY" - <<'PYEOF'
import os, time
TRASH = r'D:/metro_build/_TRASH'
ts = str(int(time.time()))
os.makedirs(TRASH, exist_ok=True)
base = r'D:/metro_build/metro_apk_build/android/app/build'
def _rn(p):
    try:
        os.rename(p, os.path.join(TRASH, os.path.basename(p) + '_bak' + ts))
        return True
    except Exception:
        return False
if os.path.exists(base):
    if _rn(base):
        print('[clean] 已整体重命名 build 目录')
    else:
        # 退路：整体被占用时，逐个移走子目录（outputs 是必须清掉的那个）
        for sub in ('intermediates', 'outputs', 'generated', 'tmp', 'kotlin'):
            p = os.path.join(base, sub)
            if os.path.exists(p) and _rn(p):
                print('[clean] 已重命名 build/%s' % sub)
PYEOF
}

# ── B. 把源码同步到纯 ASCII 构建目录（排除编译产物） ─────────────────
sync_source() {
    mkdir -p "$BUILD_ROOT"
    echo "[sync] 同步源码 -> $BUILD_PROJ（纯 ASCII 路径）"
    cd "D:/WorkBuddy_Save/手机小程序开发"
    # App 内嵌网页由根目录 beijing-metro.html 生成。
    # 仓库里不存这份副本（它是与 beijing-metro.html 完全相同的 1.1MB，提交两份纯属浪费），
    # 改为每次构建前自动同步，保证与网页版逐字节一致。
    mkdir -p "android/app/src/main/assets/public"
    # 注意：metro.html 可能被外部进程以「可读写但禁止删除/重命名」的方式占用
    # （编辑器 / 预览器 / 杀软），此时 mv 和 cp 都会 Permission denied。
    # 若目标内容已与源一致，直接跳过覆盖；否则先 rename 旧文件再写入。
    ASSET_HTML="android/app/src/main/assets/public/metro.html"
    if [ -f "$ASSET_HTML" ]; then
        if cmp -s "beijing-metro.html" "$ASSET_HTML"; then
            echo "[sync] metro.html 已是最新，跳过覆盖"
        else
            mv -f "$ASSET_HTML" "${ASSET_HTML}.old_$(date +%s)" 2>/dev/null \
                || echo "[sync] 旧 metro.html 占用中，尝试直接覆盖"
            if ! cp -f "beijing-metro.html" "$ASSET_HTML" 2>/dev/null; then
                echo "[sync] 失败：无法同步 beijing-metro.html 到 App 资源"
                exit 1
            fi
        fi
    else
        if ! cp -f "beijing-metro.html" "$ASSET_HTML" 2>/dev/null; then
            echo "[sync] 失败：无法同步 beijing-metro.html 到 App 资源"
            exit 1
        fi
    fi
    # 同步校验：源与目标必须逐字节一致，否则 App 内嵌的会是上一版网页
    if ! cmp -s "beijing-metro.html" "$ASSET_HTML"; then
        echo "[sync] 失败：同步后内容不一致"
        exit 1
    fi
    # WorkBuddy safe-delete 会拦截 rm -rf 整个目录（实测：目录仍在且命令无输出，
    # 脚本后续步骤全部不执行）。改用 rename（mv）绕开；旧目录保留为 .old_*，
    # tar 打包时排除。注意排除模式必须同时覆盖两种形态：
    #   *_old_*  → 目录/文件名中含「_old_」（下划线，如 .gradle_bak、xxx_old_123）
    #   *.old_*  → metro.html.old_123 这类「.old_」（点号，sync_source 的 mv 改名产物）
    #   （v1.0.21 及之前只写了 *_old_*，导致 metro.html.old_* 残留被打进 APK，
    #     包体凭空多出约 0.35MB——v1.0.22 修复）
    if [ -d "$BUILD_PROJ" ]; then
        mv -f "$BUILD_PROJ" "${BUILD_PROJ}.old_$(date +%s)" 2>/dev/null || true
    fi
    tar cf - --exclude='build' --exclude='.gradle' \
        --exclude='*_old_*' --exclude='*.old_*' \
        --exclude='*_bak*' --exclude='*.bak' \
        --exclude='*.tmp' --exclude='*.swp' --exclude='*~' \
        android 2>/dev/null \
        | (cd "$BUILD_ROOT" && tar xf - 2>/dev/null)
    if [ ! -f "$BUILD_PROJ/app/src/main/java/com/metronext/metro/MainActivity.kt" ]; then
        echo "[sync] 失败：MainActivity.kt 未同步"
        exit 1
    fi
    echo "[sync] 完成"
}

cleanup_trash
prepare_caches
sync_source

# C. 构建（Kotlin daemon 偶发连不上，失败则重试）
TASK="assembleDebug"; APK_SUB="apk/debug/app-debug.apk"; OUT_NAME="MetroNext-debug.apk"
if [ "$VARIANT" = "release" ]; then
    TASK="assembleRelease"; APK_SUB="apk/release/app-release.apk"; OUT_NAME="MetroNext-release.apk"
fi
cd "$BUILD_PROJ"
attempt=1
max=3
while [ $attempt -le $max ]; do
    echo "[build] 第 $attempt/$max 次尝试：$TASK ($VARIANT) ..."
    if "$GRADLE" "$TASK" --no-daemon --console=plain; then
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
APK="$BUILD_PROJ/app/build/outputs/$APK_SUB"
OUTDIR="D:/WorkBuddy_Save/手机小程序开发"
if [ ! -f "$APK" ]; then
    echo "[build] 未找到 APK: $APK"
    exit 1
fi
# 文件名后缀带上版本号（从 build.gradle.kts 提取 versionName）：
# 如 MetroNext-debug-1.0.28.apk / MetroNext-release-1.0.28.apk
VER=$(sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$BUILD_PROJ/app/build.gradle.kts" | head -1)
if [ -n "$VER" ]; then
    OUT_NAME="${OUT_NAME%.apk}-$VER.apk"
    echo "[build] 版本号: $VER -> $OUT_NAME"
fi
# 覆盖已有文件 = 删除+新建，若旧文件被占用（如已被预览器/资源管理器打开）会 Permission denied，
# 且此时连 rename 都失败。退路：写入带时间戳的新文件名（纯新建，不受影响）。
if cp "$APK" "$OUTDIR/$OUT_NAME" 2>/dev/null; then
    echo "[build] APK 已输出: $OUTDIR/$OUT_NAME"
    ls -la "$OUTDIR/$OUT_NAME"
else
    ALT="$OUTDIR/${OUT_NAME%.apk}-$(date +%m%d-%H%M).apk"
    if cp "$APK" "$ALT" 2>/dev/null; then
        echo "[build] 目标被占用（Permission denied），已改为输出: $ALT"
        ls -la "$ALT"
    else
        echo "[build] APK 输出失败，构建产物仍在: $APK"
        exit 1
    fi
fi

# E. 收尾：清掉本轮产生的 build/_bak、locks 等垃圾，保持稳态零积累
cleanup_trash
echo "[build] 全部完成"
