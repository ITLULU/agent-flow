#!/usr/bin/env bash
# agent-flow 本地后端启动/重启脚本。
#
# 凭证纪律：本脚本**不含任何密钥**，只从当前 shell 环境继承；不 source 任何 secrets 文件、
# 不打印任何变量值（只报"有没有"）。请在已经 export 好 PG_DB_PASSWORD / AI_API_KEY 的交互终端里跑它。
#
# 用法：
#   bash scripts/run-server.sh              # 前台检查 + 后台启动，日志在 /tmp/agentflow-8080.log
#   SERVER_PORT=8081 bash scripts/run-server.sh
set -euo pipefail

PORT="${SERVER_PORT:-8080}"
JAVA_BIN="${JAVA_HOME:-/Users/a/tools/jdk-21.0.12.1+1/Contents/Home}/bin/java"
LOG="${AF_LOG:-/tmp/agentflow-${PORT}.log}"
cd "$(dirname "$0")/../server/agent-flow-server"
JAR=server-application/target/server-application-0.1.0-SNAPSHOT.jar

# 本机两把百炼 key 的变量名与 yml 占位符链不完全同名（按量 key 在 AI_API_KEY1，容器垫片落回 AI_API_KEY）。
# 这里双向补齐：哪个有值就把另一个也填上，让"从哪个 shell 起"都不影响装配。只映射，不落地、不回显。
if [[ -z "${AI_API_KEY:-}" && -n "${AI_API_KEY1:-}" ]]; then
  export AI_API_KEY="$AI_API_KEY1"
elif [[ -z "${AI_API_KEY1:-}" && -n "${AI_API_KEY:-}" ]]; then
  export AI_API_KEY1="$AI_API_KEY"
fi

missing=()
[[ -n "${AI_API_KEY:-}" ]] || missing+=("AI_API_KEY / AI_API_KEY1（两把至少要有一把）")
[[ -n "${PG_DB_PASSWORD:-}" ]] || missing+=("PG_DB_PASSWORD")
if ((${#missing[@]})); then
  echo "缺少环境变量：${missing[*]}" >&2
  echo "  → 注意 zsh 的坑：在 .zshrc 里写 VAR=xxx（没有 export）时，你自己终端里能看到它，" >&2
  echo "    但脚本和 java 子进程拿不到。补一句 export 即可：export AI_API_KEY1 PG_DB_PASSWORD" >&2
  echo "  → 缺 PG_DB_PASSWORD 时 Flyway 会在建 bean 阶段直接失败，进程退出。" >&2
  exit 1
fi

# 端口占用：上一轮遗留的 java 进程会让新实例启动即失败；而且那个旧进程的 jar 已被本次构建原地替换，
# 继续跑会在运行期抛 NoClassDefFoundError（日志里表现为满屏 ThrowableProxy 找不到）。
if pid=$(lsof -tnP -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null | head -1) && [[ -n "${pid}" ]]; then
  echo "端口 ${PORT} 已被进程 ${pid} 占用。" >&2
  echo "  → kill ${pid} 后重跑本脚本。（旧实例是本次构建之前的 jar，已在运行期报类加载错误，不要留着）" >&2
  exit 1
fi

if [[ ! -f "${JAR}" ]]; then
  echo "找不到 ${JAR}，先在 server/agent-flow-server 下跑：mvn -DskipTests package" >&2
  exit 1
fi

nohup "${JAVA_BIN}" -jar "${JAR}" --server.port="${PORT}" >"${LOG}" 2>&1 &
pid=$!
echo "已后台启动：pid=${pid} port=${PORT} log=${LOG}"
sleep 6
if kill -0 "${pid}" 2>/dev/null; then
  echo "进程存活。自检摘要："
  grep -E "Started AgentFlowApplication|StartupSelfCheck - (llm|image|vision) endpoint|Tomcat started" "${LOG}" | tail -5 \
    || echo "  （还在启动中，看日志：tail -f ${LOG}）"
  # 空 key 是"进程活着但一到执行就报配置错"的隐形状态，这里当场说破，别让人等到跑流水线才发现
  if grep -q "llm endpoint .*网关未装配" "${LOG}"; then
    echo "⚠️ llm 网关没装配：agentflow.nodes.llm.api-key 解析为空（脚本已双向映射过 AI_API_KEY/AI_API_KEY1，仍是空说明这个 shell 里两把都没 export）。" >&2
  fi
else
  echo "启动失败，最后 20 行日志：" >&2
  tail -20 "${LOG}" >&2
  exit 1
fi
