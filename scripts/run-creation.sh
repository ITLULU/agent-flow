#!/usr/bin/env bash
# 创作流水线一键驱动：热点/链接/话题 → 文案 → 指定比例主图 → 本地归档 → 草稿发布 → 成品下载。
# 依赖：curl + jq。示例见文件末尾 usage。
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
MODE="xhs"                 # xhs | wechat
SOURCE="auto"              # auto | hot | link | topic
TOPIC=""
URL=""
RATIO=""
LENGTH=""
AUDIENCE=""
TONE=""
EXTRA=""
ILLUSTRATIONS=0
PUBLISH="auto"             # auto | local | none
CRED=""
THEME="moss"               # wechat.draft 排版配色：moss | azure | amber | ink
MAX_SCORE="25"             # copy.originality 相似度上限（0-100）
STRICT=0                   # 1 = 超阈值直接让链路失败
NO_ORIGINALITY=0           # 1 = 不插原创度校验节点
OUT_DIR="./creation-out"
DRY_RUN=0

usage() {
    cat <<'EOF'
用法: scripts/run-creation.sh [选项]

  --mode xhs|wechat     创作渠道（默认 xhs）
  --topic "..."         主题/想法；省略时自动取上游热点标题或文章标题
  --url "https://..."   输入一篇公众号文章/任意链接（SOURCE 自动为 link）
  --source hot|link|topic  取数方式（默认 auto：有 --url 走 link，有 --topic 走 topic，否则 hot）
  --ratio 3:4|1:1|...   主图比例，支持预设名（xhs/landscape/story/banner/wechat-share/wechat-small）或 a:b
  --length 1200         正文字数（wechat 默认 1500 / xhs 默认 600）
  --audience/--tone/--extra  目标读者 / 语气风格 / 补充要求
  --illustrations 2     额外配图张数（image.cover kind=illustration，wechat 正文配图会走 uploadimg 换永久直链）
  --theme moss|azure|amber|ink  公众号正文排版配色（默认 moss）
  --max-score 25        原创度相似度上限（copy.originality 超过它算抄袭风险）
  --strict              相似度超阈值即整条失败（默认只标降级，稿子照发）
  --no-originality      不插 copy.originality 节点（默认只在有原文可比时插入）
  --publish auto|local|none  auto=xhs 走 xhs.draft、wechat 走 wechat.draft
  --credential-id 12    公众号凭证库 id（微信通道建议用库凭证而非环境变量）
  --out ./creation-out  成品下载目录
  --dry-run             只打印提交的 DSL，不落库不执行

鉴权（三选一）：
  AF_TOKEN=xxxx         直接注入 JWT
  AF_USER=xx AF_PASS=xx 脚本自动登录取 JWT
  两者都不给            依赖 agentflow.security.enabled=false（本地默认）或 X-API-Token
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --mode) MODE=$2; shift 2 ;;
        --topic) TOPIC=$2; shift 2 ;;
        --url) URL=$2; shift 2 ;;
        --source) SOURCE=$2; shift 2 ;;
        --ratio) RATIO=$2; shift 2 ;;
        --length) LENGTH=$2; shift 2 ;;
        --audience) AUDIENCE=$2; shift 2 ;;
        --tone) TONE=$2; shift 2 ;;
        --extra) EXTRA=$2; shift 2 ;;
        --illustrations) ILLUSTRATIONS=$2; shift 2 ;;
        --theme) THEME=$2; shift 2 ;;
        --max-score) MAX_SCORE=$2; shift 2 ;;
        --strict) STRICT=1; shift ;;
        --no-originality) NO_ORIGINALITY=1; shift ;;
        --publish) PUBLISH=$2; shift 2 ;;
        --credential-id) CRED=$2; shift 2 ;;
        --out) OUT_DIR=$2; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "未知参数: $1" >&2; usage; exit 2 ;;
    esac
done

command -v jq >/dev/null || { echo "需要 jq：brew install jq 或用 /usr/bin/jq" >&2; exit 1; }

case "$MODE" in
    xhs|xiaohongshu) COPY_MODE="xiaohongshu"; DEFAULT_RATIO="3:4"; KIND="note" ;;
    wechat|mp)       COPY_MODE="wechat";      DEFAULT_RATIO="wechat-share"; KIND="article" ;;
    *) echo "--mode 只能是 xhs 或 wechat" >&2; exit 2 ;;
esac
[[ -z "$RATIO" ]] && RATIO="$DEFAULT_RATIO"

if [[ "$SOURCE" == auto ]]; then
    if [[ -n "$URL" ]]; then SOURCE=link
    elif [[ -n "$TOPIC" ]]; then SOURCE=topic
    else SOURCE=hot; fi
fi

# ---- 鉴权 ----
AUTH_HEADER=()
if [[ -n "${AF_TOKEN:-}" ]]; then
    AUTH_HEADER=(-H "Authorization: Bearer $AF_TOKEN")
elif [[ -n "${AF_USER:-}" && -n "${AF_PASS:-}" ]]; then
    AF_TOKEN=$(curl -fsS -X POST "$BASE_URL/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "$(jq -n --arg u "$AF_USER" --arg p "$AF_PASS" '{username:$u,password:$p}')" \
        | jq -r '.data.token')
    AUTH_HEADER=(-H "Authorization: Bearer $AF_TOKEN")
elif [[ -n "${AGENTFLOW_API_TOKEN:-}" ]]; then
    AUTH_HEADER=(-H "X-API-Token: $AGENTFLOW_API_TOKEN")
fi

api() { # api METHOD PATH [BODY] —— 非 2xx 时把服务端错误体原样打出再退出（A0400/C0500 原因可读）
    local method=$1 path=$2 body=${3:-} resp code
    if [[ -n "$body" ]]; then
        resp=$(curl -sS -w '\n%{http_code}' -X "$method" "$BASE_URL$path" \
            ${AUTH_HEADER[@]+"${AUTH_HEADER[@]}"} \
            -H 'Content-Type: application/json; charset=utf-8' --data-binary "$body")
    else
        resp=$(curl -sS -w '\n%{http_code}' -X "$method" "$BASE_URL$path" \
            ${AUTH_HEADER[@]+"${AUTH_HEADER[@]}"})
    fi
    code=${resp##*$'\n'}
    resp=${resp%$'\n'*}
    if [[ "$code" != 2* ]]; then
        echo "HTTP $code ← $method $path" >&2
        printf '%s\n' "$resp" | jq . >&2 2>/dev/null || printf '%s\n' "$resp" >&2
        exit 1
    fi
    printf '%s' "$resp"
}

# ---- 组装 DSL ----
NODES='[]' EDGES='[]' PREV=''
link() { [[ -n "$PREV" ]] && EDGES=$(jq --arg f "$PREV" --arg t "$1" '. + [{from:$f,to:$t}]' <<<"$EDGES"); PREV=$1; }
push() { NODES=$(jq --argjson n "$1" '. + [$n]' <<<"$NODES"); }

if [[ "$SOURCE" == hot ]]; then
    push "$(jq -n '{id:"a",type:"hotspot.fetch",params:{limit:20}}')"; link a
elif [[ "$SOURCE" == link ]]; then
    [[ -n "$URL" ]] || { echo "--source link 需要 --url" >&2; exit 2; }
    push "$(jq -n --arg u "$URL" '{id:"a",type:"article.extract",params:{url:$u}}')"; link a
fi

COPY_PARAMS=$(jq -n --arg m "$COPY_MODE" --arg t "$TOPIC" \
    --arg a "$AUDIENCE" --arg o "$TONE" --arg x "$EXTRA" --arg l "$LENGTH" \
    'reduce ({topic:$t,audience:$a,tone:$o,extra:$x,length:$l} | to_entries[] | select(.value!="")) as $e
     ({}; .[$e.key] = $e.value) | . + {mode:$m}')
push "$(jq -n --argjson p "$COPY_PARAMS" '{id:"b",type:"copy.write",params:$p}')"
link b

push "$(jq -n --arg r "$RATIO" '{id:"c",type:"image.cover",params:{kind:"cover",ratio:$r,n:1}}')"; link c

if [[ "$ILLUSTRATIONS" -gt 0 ]]; then
    push "$(jq -n --arg r "$RATIO" --argjson n "$ILLUSTRATIONS" \
        '{id:"d",type:"image.cover",params:{kind:"illustration",ratio:$r,n:$n}}')"; link d
fi

push "$(jq -n '{id:"e",type:"publish.local",params:{}}')"; link e

# 原创度校验只在"有原文可比"时才插：话题/热榜链路没有原始文章，插进去只会得到一条
# "原文缺失"的降级噪声，反而让人以为稿子有问题。
# 位置刻意排在 publish.local 之后、草稿节点之前——它是**发布闸门**而不是创作前置：
# --strict 超阈值时草稿不外发，但稿子已经落库落盘（"推送失败也要本地留档"同一条口径）。
if [[ "$NO_ORIGINALITY" == 0 && "$SOURCE" == link ]]; then
    push "$(jq -n --arg ms "$MAX_SCORE" --argjson st "$STRICT" \
        '{id:"f",type:"copy.originality",params:{maxScore:($ms|tonumber),strict:($st==1),useLlm:true}}')"
    link f
fi

case "$PUBLISH" in
    auto) [[ "$COPY_MODE" == wechat ]] && PUBLISH=wechat || PUBLISH=xhs ;;
esac
if [[ "$PUBLISH" == xhs ]]; then
    push "$(jq -n '{id:"g",type:"xhs.draft",params:{}}')"; link g
elif [[ "$PUBLISH" == wechat ]]; then
    WP=$(jq -n --arg c "$CRED" --arg th "$THEME" \
        'if $c=="" then {} else {credentialId:($c|tonumber)} end + {theme:$th}')
    push "$(jq -n --argjson p "$WP" '{id:"g",type:"wechat.draft",params:$p}')"
    link g
fi

DSL=$(jq -n --arg name "creation-$MODE-$(date +%Y%m%d-%H%M%S)" \
    --argjson nodes "$NODES" --argjson edges "$EDGES" \
    '{name:$name,trigger:{type:"manual"},nodes:$nodes,edges:$edges}')

echo "== DSL =="; echo "$DSL" | jq .
if [[ "$DRY_RUN" == 1 ]]; then
    exit 0
fi

WF_ID=$(jq -r '.data.workflowId' <<<"$(api POST /api/workflows "$DSL")")
EX_ID=$(jq -r '.data.executionId' <<<"$(api POST "/api/workflows/$WF_ID/executions" '{}')")
echo "== 执行 $EX_ID（workflow $WF_ID）=="

STATUS=RUNNING
for _ in $(seq 1 120); do
    DET=$(api GET "/api/executions/$EX_ID")
    STATUS=$(jq -r '.data.status' <<<"$DET")
    [[ "$STATUS" != RUNNING && "$STATUS" != QUEUED ]] && break
    sleep 2
done
jq -r '.data.nodes[]? | "  \(.nodeId) \(.nodeType) → \(.status)（\(.attempts) 次 / \(.durationMs // 0)ms\(.reason // "" | if .=="" then "" else " :: " + . end)）"' <<<"$DET"
# 抄袭风险必须一眼可见，不用翻执行明细
ORIG=$(jq -r '[.data.nodes[]? | select(.nodeType=="copy.originality")][0].output
    | select(.!= null)
    | "similarityScore=\(.similarityScore) 风险=\(.riskLevel) 口径=\(.method) 最长连续重合=\(.maxCommonRun)字（上限 '"$MAX_SCORE"'）"
      + (if .llmNote == null then "" else "\n  !! 模型评分缺失（\(.llmNote)）：这一条只是本地 5-gram 口径，看不出换词照抄，不能当原创证明" end)' <<<"$DET")
[[ -n "$ORIG" ]] && echo "== 原创度: $ORIG =="
echo "== 终态: $STATUS =="

if [[ "$STATUS" == RUNNING || "$STATUS" == QUEUED ]]; then
    echo "!! 轮询超时（约 240s）执行仍未落定：$EX_ID，稍后 curl $BASE_URL/api/executions/$EX_ID 复查" >&2
    exit 1
fi

# 成品 id 直接取 publish.local 的输出（本次执行写下的那一条），比"库里最新一条"可靠：
# 并发跑多条流水线时后者会拿错稿。
ART_ID=$(jq -r '[.data.nodes[]? | select(.nodeType=="publish.local")][0].output.artifactId // empty' <<<"$DET")

# 外发（wechat.draft / xhs.draft）失败时，创作 + 归档已经落定：成品行在 PG、文件在 library 目录。
# 这里不能一走了之——先公告物在哪、再退出非 0，让"推送失败"和"稿子没丢"同时可见。
if [[ "$STATUS" != SUCCESS && "$STATUS" != SUCCESS_WITH_DEGRADED ]]; then
    echo "!! 失败节点明细:" >&2
    jq -r '.data.nodes[]? | select(.status=="FAILED") | "   \(.nodeType): \(.reason // "无原因")"' <<<"$DET" >&2
    if [[ -n "$ART_ID" ]]; then
        echo "!! 但本地归档已成功：成品 #$ART_ID（curl $BASE_URL/api/artifacts/$ART_ID/files 可取文件清单）" >&2
    else
        echo "!! 且未生成成品（失败发生在 publish.local 之前）" >&2
    fi
    exit 1
fi

if [[ -z "$ART_ID" ]]; then
    # /api/artifacts 是分页对象 {items,nextCursor}（P7-3#13），不是裸数组；写成 .data[] 会让 jq 报
    # "Cannot index array with string"，取成品这一步直接崩
    ART_ID=$(jq -r "[.data.items[] | select(.kind==\"$KIND\")][0].id // empty" <<<"$(api GET "/api/artifacts?kind=$KIND&limit=1")")
fi
[[ -n "$ART_ID" ]] || { echo "成品库没有 kind=$KIND 记录" >&2; exit 1; }
mkdir -p "$OUT_DIR"
while IFS= read -r F; do
    [[ -n "$F" ]] || continue
    mkdir -p "$OUT_DIR/$(dirname "$F")"
    # 正文文件名现在是 {标题}-{时间戳}.md，中文标题直接把汉字塞进 query 会被 Tomcat 判 400
    # （请求目标必须是 ASCII，非 ASCII 要百分号编码——画布侧用 encodeURIComponent，这里同口径）
    ENC=$(jq -rn --arg p "$F" '$p|@uri')
    curl -fsS ${AUTH_HEADER[@]+"${AUTH_HEADER[@]}"} -o "$OUT_DIR/$F" "$BASE_URL/api/artifacts/$ART_ID/file?path=$ENC"
    echo "  取回 $OUT_DIR/$F"
done <<<"$(api GET "/api/artifacts/$ART_ID/files" | jq -r '.data[]')"
echo "== 成品 #$ART_ID（kind=$KIND）已下载到 $OUT_DIR =="

# 示例：
#   scripts/run-creation.sh --mode xhs --topic "秋天适合读完的书" --ratio 3:4
#   # 外链改写：自动插 copy.originality，正文配图走 uploadimg 换永久直链
#   scripts/run-creation.sh --mode wechat --url https://mp.weixin.qq.com/s/xxxx \
#       --ratio 2.35:1 --illustrations 2 --theme moss
#   scripts/run-creation.sh --mode wechat --url https://... --strict --max-score 20   # 超阈值直接失败
#   scripts/run-creation.sh --mode xhs --source hot --illustrations 2 --publish local
