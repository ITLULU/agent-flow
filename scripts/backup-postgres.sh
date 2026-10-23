#!/usr/bin/env bash
# agent-flow PG16 + 产物库备份、异地副本、定时调度与失败告警（评审 P6 #9）。
# 只依赖 docker compose + 容器里的 pg_dump/psql，不碰宿主 PG，也不要把口令写进任何文件：
# DB 口令由 compose 的 secrets/db_password 注入容器，脚本只通过 `docker compose exec` 借用。
#
#   bash scripts/backup-postgres.sh backup    [--compose DIR] [--keep N] [--out DIR]
#   bash scripts/backup-postgres.sh verify    FILE
#   bash scripts/backup-postgres.sh drill     [--compose DIR] [--file FILE] [--out DIR]
#   bash scripts/backup-postgres.sh run-daily [--drill]      # 定时任务入口：备份+校验+异地副本
#   bash scripts/backup-postgres.sh schedule  [HH:MM]        # macOS launchd 登记；Linux 打印等价 crontab
#   bash scripts/backup-postgres.sh unschedule
#
# backup：pg_dump -Fc 落 <compose>/backups/agentflow-<时间戳>.dump（0600），并把产物库目录（artifact
#         文件在磁盘上、不在 PG 里）打包成同时间戳的 -library.tgz，两者一起轮转与送异地副本。
# verify ：只做结构可读检查，只能证明"这不是个空文件/坏文件"；恢复可用性必须靠 drill，两者不可互相顶替。
# drill ：把 dump 恢复进临时库 agentflow_drill_<时间戳>，逐表计数与原库比对，跑完 DROP 临时库
#         （失败路径由 trap 兜底，绝不在实例里留临时库）。SOURCE_DB 可指向 scratch 库——
#         拿空库演练只会得到 0==0 的假绿，恢复演练要有内容才算演过。
#         没有做过恢复演练的备份等于没有备份——这一步才是本脚本的意义。
# 异地副本：BACKUP_OFFSITE_DIR=挂载盘/NFS/第二台机器的共享目录（cp + cmp 逐字节核对）；
#           BACKUP_OFFSITE_CMD=自定义上传命令（rclone/aws s3 cp/gsutil 等），脚本把两个文件路径作为
#           参数追加给它。对象存储需要凭证，凭证只走环境变量或云 CLI 自己的配置，绝不写进本仓库。
# 告警：任何失败都在 ALERT_LOG 落一条 FATAL 行并返回非 0；设了 BACKUP_ALERT_WEBHOOK 再 POST 一条 JSON。
set -euo pipefail

SERVICE_DB=agent-flow-db
SERVICE_APP=agent-flow-server
SOURCE_DB="${SOURCE_DB:-agentflow}"   # 可覆盖：恢复演练要能对"有内容的库"做，0 行比 0 行证明不了恢复可用
DB_USER=agentflow
TABLES=(workflow artifact execution execution_node_log user_credentials users flyway_schema_history)

# 默认指向部署目录（compose 与 secrets 都在那里）
COMPOSE_DIR="${COMPOSE_DIR:-$HOME/source/SpringCloudAdmin/docker-compose/agent-flow}"
KEEP="${KEEP:-7}"
# OUT_DIR / FILE 允许 env 或 --out/--file 覆盖，未给时才在 parse() 里落默认目录
OUT_DIR="${OUT_DIR:-}"
FILE="${FILE:-}"
# 产物库目录：与库配套，只备份 PG 会丢掉 artifact 指向的真实文件
LIBRARY_DIR="${LIBRARY_DIR:-$COMPOSE_DIR/data/library}"
OFFSITE_DIR="${BACKUP_OFFSITE_DIR:-}"
OFFSITE_CMD="${BACKUP_OFFSITE_CMD:-}"
ALERT_WEBHOOK="${BACKUP_ALERT_WEBHOOK:-}"
ALERT_LOG="${BACKUP_ALERT_LOG:-}"

err() { printf 'FATAL: %s\n' "$1" >&2; exit 1; }

ensure_docker_on_path() { # launchd/cron 给的是极简 PATH，Docker Desktop 的 CLI 在 ~/.docker/bin：找不到就补，补不到就明确报错
    command -v docker >/dev/null 2>&1 && return 0
    for d in "$HOME/.docker/bin" /usr/local/bin /opt/homebrew/bin; do
        if [[ -x "$d/docker" ]]; then PATH="$d:$PATH"; export PATH; return 0; fi
    done
    err "找不到 docker CLI（备份依赖 docker compose）"
}

parse() { # 解析 --compose/--keep/--out/--file；裸位置参数视为待检查的 dump 文件
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --compose) COMPOSE_DIR="$2"; shift 2;;
            --keep)    KEEP="$2"; shift 2;;
            --file)    FILE="$2"; shift 2;;
            --out)     OUT_DIR="$2"; shift 2;;
            --drill)   RUN_DRILL=1; shift;;
            *)         [[ -z "$FILE" ]] && FILE="$1"; shift;;
        esac
    done
    [[ -f "$COMPOSE_DIR/docker-compose.yml" ]] || err "compose 目录不对：$COMPOSE_DIR"
    ensure_docker_on_path
    OUT_DIR="${OUT_DIR:-$COMPOSE_DIR/backups}"
    ALERT_LOG="${ALERT_LOG:-$OUT_DIR/backup-alerts.log}"
    mkdir -p "$OUT_DIR"; chmod 700 "$OUT_DIR"
}

alert() { # 备份失败必须留下痕迹：日志一行 FATAL + 可选 webhook，定时任务里没人看 stdout
    ts=$(date '+%Y-%m-%d %H:%M:%S')
    printf 'FATAL %s %s\n' "$ts" "$1" >> "$ALERT_LOG"
    chmod 600 "$ALERT_LOG" 2>/dev/null || true
    printf 'ALERT: %s（已记入 %s）\n' "$1" "$ALERT_LOG" >&2
    if [[ -n "$ALERT_WEBHOOK" ]]; then
        payload="{\"source\":\"agent-flow-backup\",\"message\":\"$1\",\"at\":\"$ts\"}"
        curl -fsS -m 10 -H 'content-type: application/json' -d "$payload" "$ALERT_WEBHOOK" >/dev/null 2>&1 \
            || printf 'WARN: 告警 webhook 投递失败（不影响备份结论，日志已留痕）\n' >&2
    fi
}

db() { # db SQL -> 输出（走 -tA，便于比对）
    docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T "$SERVICE_DB" \
        psql -U "$DB_USER" -d "$1" -tA -c "$2"
}

rotate() { # 轮转：BSD ls/head 不支持负数计数，也不能用 mapfile（本机 bash 3.2）——先数再删最老的
    pattern="$1"
    files=$(ls -1 "$OUT_DIR"/$pattern 2>/dev/null | sort || true)
    n=$(printf '%s\n' "$files" | grep -c . || true)
    if [ "${n:-0}" -gt "$KEEP" ]; then
        printf '%s\n' "$files" | head -n "$((n - KEEP))" | while IFS= read -r f; do
            [[ -n "$f" ]] && { rm -f "$f"; echo "轮转删除: $f"; }
        done
    fi
}

offsite() { # 异地/第二介质副本：先本地目录（cp+cmp 校验），再交给自定义上传命令
    sent=0
    if [[ -n "$OFFSITE_DIR" ]]; then
        mkdir -p "$OFFSITE_DIR"; chmod 700 "$OFFSITE_DIR"
        for f in "$@"; do
            [[ -f "$f" ]] || continue
            cp -f "$f" "$OFFSITE_DIR/"; chmod 600 "$OFFSITE_DIR/$(basename "$f")"
            cmp -s "$f" "$OFFSITE_DIR/$(basename "$f")" || { alert "异地副本字节不一致: $(basename "$f")"; return 1; }
            echo "异地副本: $OFFSITE_DIR/$(basename "$f")（逐字节一致）"
            sent=$((sent + 1))
        done
    fi
    if [[ -n "$OFFSITE_CMD" ]]; then
        # shellcheck disable=SC2086
        $OFFSITE_CMD "$@" && echo "异地副本命令已执行: $OFFSITE_CMD" && sent=$((sent + 1)) \
            || { alert "异地副本命令失败: $OFFSITE_CMD"; return 1; }
    fi
    [[ "$sent" -gt 0 ]] || echo "WARN: 未配置异地副本（BACKUP_OFFSITE_DIR / BACKUP_OFFSITE_CMD），当前只有本地一份"
    return 0
}

latest_dump() { ls -1 "$OUT_DIR"/agentflow-*.dump 2>/dev/null | sort | tail -1 || true; }

do_backup() {
    ts=$(date +%Y%m%d-%H%M%S)
    dump="$OUT_DIR/agentflow-$ts.dump"
    docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T "$SERVICE_DB" \
        pg_dump -U "$DB_USER" -Fc --no-owner --no-privileges "$SOURCE_DB" > "$dump"
    chmod 600 "$dump"
    bytes=$(wc -c < "$dump" | tr -d ' ')
    [[ "$bytes" -gt 0 ]] || { alert "dump 为 0 字节，备份不可信"; err "dump 为 0 字节，备份不可信"; }
    echo "备份完成: $dump ($bytes 字节)"

    tgz=""
    if [[ -d "$LIBRARY_DIR" ]]; then
        tgz="$OUT_DIR/agentflow-$ts-library.tgz"
        # -h：产物目录里可能有指向别处的软链，保留原样；空目录也打包，让"当天没有产物"成为可证明的事实
        tar -czf "$tgz" -C "$(dirname "$LIBRARY_DIR")" "$(basename "$LIBRARY_DIR")"
        chmod 600 "$tgz"
        cnt=$(tar -tzf "$tgz" | grep -vc '/$' || true)
        echo "产物库归档: $tgz（$(wc -c < "$tgz" | tr -d ' ') 字节，$cnt 个文件）"
    else
        echo "WARN: 产物库目录不存在（$LIBRARY_DIR），本次只备了库"
    fi

    rotate 'agentflow-*.dump'
    rotate 'agentflow-*-library.tgz'
    offsite "$dump" ${tgz:+"$tgz"}
}

do_verify() {
    [[ -f "$FILE" ]] || err "verify 需要一个 dump 文件：bash $0 verify <FILE>"
    docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T -i "$SERVICE_DB" \
        pg_restore --list < "$FILE" > /dev/null
    echo "dump 结构可读: $FILE"
}

do_drill() {
    if [[ -z "$FILE" ]]; then
        FILE=$(latest_dump)
        [[ -n "$FILE" ]] || { alert "$OUT_DIR 下没有 dump，无法做恢复演练"; err "$OUT_DIR 下没有 dump，先跑 backup"; }
    fi
    [[ -f "$FILE" ]] || err "文件不存在：$FILE"
    target="agentflow_drill_$(date +%s)"
    echo "恢复演练: $(basename "$FILE") -> 临时库 $target"
    # pg_restore 失败会因 set -e 直接退出，临时库就留在实例里了——用 trap 保证任何退出路径都清干净
    trap 'docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T "$SERVICE_DB" \
            psql -U "$DB_USER" -d postgres -c "DROP DATABASE IF EXISTS $target WITH (FORCE);" >/dev/null 2>&1 || true; \
          printf "清理: 临时库 %s 已删除\n" "$target" >&2' EXIT
    # 临时库建在原实例上，跑完即删，绝不动 prod 库
    db postgres "CREATE DATABASE $target OWNER $DB_USER;" > /dev/null
    docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T -i "$SERVICE_DB" \
        pg_restore -U "$DB_USER" -d "$target" --no-owner --no-privileges < "$FILE"
    echo "pg_restore 完成，开始逐表计数比对"
    fail=0
    for t in ${TABLES[@]+"${TABLES[@]}"}; do
        src=$(db "$SOURCE_DB" "select count(*) from $t;" || echo "-")
        dst=$(db "$target" "select count(*) from $t;" || echo "-")
        mark="ok"; [[ "$src" == "$dst" ]] || { mark="MISMATCH"; fail=1; }
        printf '  %-22s prod=%s drill=%s %s\n' "$t" "$src" "$dst" "$mark"
    done
    db postgres "DROP DATABASE $target WITH (FORCE);" > /dev/null
    trap - EXIT
    echo "临时库 $target 已删除"
    [[ "$fail" == 0 ]] || { alert "恢复演练计数不一致：备份不可用于恢复"; err "计数不一致：备份不可用于恢复"; }
    echo "恢复演练通过：备份可用于整库恢复"
    # 归档文件的可恢复性只能抽验：解包看清单，不落地覆盖真产物目录
    [[ -n "$ARCHIVE_TGZ" ]] && { tar -tzf "$ARCHIVE_TGZ" > /dev/null && echo "产物归档可解包: $ARCHIVE_TGZ"; }
}

plist_path() { echo "$HOME/Library/LaunchAgents/com.agentflow.backup.plist"; }

cmd="${1:-}"; shift || true
EXTRA_ARG="${1:-}"   # schedule 的时间参数不参与 --flag 解析，避免被当成 dump 文件
parse "$@"

case "$cmd" in
    backup)  do_backup ;;
    verify)  do_verify ;;
    drill)   ARCHIVE_TGZ=$(ls -1 "$OUT_DIR"/agentflow-*-library.tgz 2>/dev/null | sort | tail -1 || true); do_drill ;;

    run-daily)
        # 定时任务入口：备份 → 校验最新 dump →（可选）恢复演练。任一步失败都走 alert()，不留静默夜。
        # pg_dump/pg_restore 自身失败也会被 || 捕获：否则 set -e 直接退出，凌晨的失败没人知道
        do_backup        || { alert "backup 阶段失败"; exit 1; }
        FILE=$(latest_dump)
        [[ -n "$FILE" ]] || { alert "备份后找不到 dump"; exit 1; }
        do_verify        || { alert "verify 阶段失败: $(basename "$FILE")"; exit 1; }
        [[ "${RUN_DRILL:-0}" == "1" ]] && { do_drill || { alert "drill 阶段失败"; exit 1; }; }
        echo "run-daily 完成：$(basename "$FILE")"
        ;;

    schedule)
        at="${EXTRA_ARG:-03:30}"
        hh="${at%%:*}"; mm="${at##*:}"
        [[ "$hh" =~ ^[0-9]{1,2}$ && "$mm" =~ ^[0-9]{1,2}$ ]] || err "时间格式要 HH:MM，例如 03:30"
        script="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
        plist=$(plist_path)
        mkdir -p "$(dirname "$plist")" "$OUT_DIR/logs"
        cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.agentflow.backup</string>
  <key>ProgramArguments</key><array>
    <string>/bin/bash</string><string>$script</string><string>run-daily</string>
    <string>--compose</string><string>$COMPOSE_DIR</string>
  </array>
  <key>StartCalendarInterval</key><dict>
    <key>Hour</key><integer>$hh</integer><key>Minute</key><integer>$mm</integer>
  </dict>
  <key>StandardOutPath</key><string>$OUT_DIR/logs/backup.out</string>
  <key>StandardErrorPath</key><string>$OUT_DIR/logs/backup.err</string>
  <key>EnvironmentVariables</key><dict>
    <key>HOME</key><string>$HOME</string>
    <key>PATH</key><string>$HOME/.docker/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
  <key>RunAtLoad</key><false/>
</dict></plist>
PLIST
        launchctl bootout "gui/$(id -u)/com.agentflow.backup" >/dev/null 2>&1 || true
        launchctl bootstrap "gui/$(id -u)" "$plist" || launchctl load "$plist"
        echo "已登记每日 $at 备份（launchd: com.agentflow.backup，日志 $OUT_DIR/logs/）"
        echo "Linux/无 launchd：crontab 里加一行  →  $mm $hh * * * /bin/bash $script run-daily --compose $COMPOSE_DIR >> $OUT_DIR/logs/backup.out 2>&1"
        ;;

    unschedule)
        launchctl bootout "gui/$(id -u)/com.agentflow.backup" >/dev/null 2>&1 \
            || launchctl unload "$(plist_path)" >/dev/null 2>&1 || true
        rm -f "$(plist_path)"
        echo "已撤销定时备份任务"
        ;;

    *)
        cat <<"USAGE"
用法:
  bash scripts/backup-postgres.sh backup    [--compose DIR] [--keep N] [--out DIR]
  bash scripts/backup-postgres.sh verify    <FILE>
  bash scripts/backup-postgres.sh drill     [--compose DIR] [--file FILE] [--out DIR]
  bash scripts/backup-postgres.sh run-daily [--drill]
  bash scripts/backup-postgres.sh schedule  [HH:MM]        # 或 unschedule
环境变量:
  SOURCE_DB 备份/比对的原库（默认 agentflow）
  LIBRARY_DIR 产物库目录（默认 <compose>/data/library）
  BACKUP_OFFSITE_DIR 异地/第二介质目录；BACKUP_OFFSITE_CMD 自定义上传命令（如 rclone copy / aws s3 cp）
  BACKUP_ALERT_WEBHOOK 失败告警地址；BACKUP_ALERT_LOG 告警日志（默认 <out>/backup-alerts.log）
USAGE
        exit 2
        ;;
esac
