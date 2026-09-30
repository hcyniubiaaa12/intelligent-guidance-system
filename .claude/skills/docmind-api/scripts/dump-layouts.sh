#!/usr/bin/env bash
# 把某个 DocumentMind 任务的全部版面块拉下来写成 JSON —— **只读接口，不重复计费**。
#
# 用法：
#   export ALIBABA_CLOUD_ACCESS_KEY_ID=xxx
#   export ALIBABA_CLOUD_ACCESS_KEY_SECRET=xxx
#   ./dump-layouts.sh <jobId> <输出文件.json>
#
# 任务号从库里拿：
#   SELECT external_job_id FROM ingest_task WHERE doc_id='...' ORDER BY created_at DESC LIMIT 1;
#
# 什么时候用它（而不是探针 docmind-api/scripts/DocMindProbe.java）：
#   - 探针的 report.txt 有 40000 字符上限，看不了整篇的逐块类型
#   - 就想知道"这份文档每个块是什么 type"、或者要拿真实产物回归本地映射/切分逻辑

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../../.." && pwd)"
BACKEND="$REPO/backend"
WORK="${PROBE_WORK:-$HERE/.work}"
mkdir -p "$WORK"

JOB_ID="${1:?用法: ./dump-layouts.sh <jobId> <输出文件.json>}"
OUT="${2:?用法: ./dump-layouts.sh <jobId> <输出文件.json>}"

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) NATIVE_SEP=';' ;;
    *)                    NATIVE_SEP=':' ;;
esac
to_native() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

if [ ! -s "$WORK/cp.txt" ]; then
    echo "[dump-layouts] 导出 llm 模块运行期 classpath ..."
    mvn -q -pl llm -f "$(to_native "$BACKEND/pom.xml")" dependency:build-classpath \
        -Dmdep.outputFile="$(to_native "$WORK/cp.txt")" -Dmdep.includeScope=runtime
fi
CP="$(cat "$WORK/cp.txt")"

echo "[dump-layouts] 编译 ..."
javac -encoding UTF-8 -cp "$CP" -d "$(to_native "$WORK/dump-classes")" "$(to_native "$HERE/DumpLayouts.java")"

echo "[dump-layouts] 拉取（只读，不计费） ..."
java -Dfile.encoding=UTF-8 -cp "$(to_native "$WORK/dump-classes")${NATIVE_SEP}${CP}" DumpLayouts "$JOB_ID" "$(to_native "$OUT")"
