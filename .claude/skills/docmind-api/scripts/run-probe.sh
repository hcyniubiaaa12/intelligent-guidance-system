#!/usr/bin/env bash
# 编译并运行 DocumentMind 探针（DocMindProbe.java）。
#
# 探针不在 Maven 构建路径上，所以这里自己拼 classpath：先让 Maven 把 llm 模块的
# 运行期依赖导出成一份清单（SDK 及其传递依赖都在里面），再 javac + java。
#
# 用法：
#   export ALIBABA_CLOUD_ACCESS_KEY_ID=xxx
#   export ALIBABA_CLOUD_ACCESS_KEY_SECRET=xxx
#   export PROBE_SAMPLE=/path/to/sample.docx        # 有 PROBE_JOB_ID 时可省
#   ./run-probe.sh
#
# 只读复用已有任务（不重新提交、不重复计费）：
#   export PROBE_JOB_ID=docmind-20260926-xxxxxxxx
#   ./run-probe.sh
#
# 完整报告写到 $PROBE_OUT/report.txt（默认 target/probe-out）——看那个文件，别看控制台，
# Windows 控制台会把中文压成乱码。

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../../../.." && pwd)"
BACKEND="$REPO/backend"
WORK="${PROBE_WORK:-$HERE/.work}"
mkdir -p "$WORK"

# Windows 上的 java 用分号分隔 classpath、且只认 Windows 路径；Git Bash 下靠 cygpath 转换
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) NATIVE_SEP=';' ;;
    *)                    NATIVE_SEP=':' ;;
esac
to_native() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

echo "[run-probe] 1/3 导出 llm 模块的运行期 classpath ..."
mvn -q -pl llm -f "$(to_native "$BACKEND/pom.xml")" dependency:build-classpath \
    -Dmdep.outputFile="$(to_native "$WORK/cp.txt")" \
    -Dmdep.includeScope=runtime
CP="$(cat "$WORK/cp.txt")"

echo "[run-probe] 2/3 编译探针 ..."
javac -encoding UTF-8 -cp "$CP" -d "$(to_native "$WORK/classes")" \
    "$(to_native "$HERE/DocMindProbe.java")"

echo "[run-probe] 3/3 运行探针 ..."
exec java -Dfile.encoding=UTF-8 -cp "$(to_native "$WORK/classes")${NATIVE_SEP}${CP}" probe.DocMindProbe
