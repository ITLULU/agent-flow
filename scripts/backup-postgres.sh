#!/usr/bin/env bash
# agent-flow PG16 备份与恢复演练（评审 P6 #9）。
# 只依赖 docker compose + 容器里的 pg_dump/psql，不碰宿主 PG，也不要把口令写进任何文件：
# DB 口令由 compose 的 secrets/db_password 注入容器，脚本只通过 `docker compose exec` 借用。
#
#   bash scripts/backup-postgres.sh backup [--compose DIR] [--keep N] [--out DIR]
#   bash scripts/backup-postgres.sh verify FILE
#   bash scripts/backup-postgres.sh drill  [--compose DIR] [--file FILE] [--out DIR]
#
# backup：pg_dump -Fc 落 <compose>/backups/agentflow-<时间戳>.dump（0600），并保留最近 N 份（默认 7）。
# verify ：只做结构可读检查，只能证明"这不是个空文件/坏文件"；恢复可用性必须靠 drill，两者不可互相顶替。
# drill ：把 dump 恢复进临时库 agentflow_drill_<时间戳>，逐表计数与原库比对，跑完 DROP 临时库
#         （失败路径由 trap 兜底，绝不在实例里留临时库）。SOURCE_DB 可指向 scratch 库——
#         拿空库演练只会得到 0==0 的假绿，恢复演练要有内容才算演过。
#         没有做过恢复演练的备份等于没有备份——这一步才是本脚本的意义。
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

err() { printf 'FATAL: %s\n' "$1" >&2; exit 1; }

parse() { # 解析 --compose/--keep/--out/--file；裸位置参数视为待检查的 dump 文件
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --compose) COMPOSE_DIR="$2"; shift 2;;
            --keep)    KEEP="$2"; shift 2;;
            --file)    FILE="$2"; shift 2;;
            --out)     OUT_DIR="$2"; shift 2;;
            *)         [[ -z "$FILE" ]] && FILE="$1"; shift;;
        esac
    done
    [[ -f "$COMPOSE_DIR/docker-compose.yml" ]] || err "compose 目录不对：$COMPOSE_DIR"
    OUT_DIR="${OUT_DIR:-$COMPOSE_DIR/backups}"
    mkdir -p "$OUT_DIR"; chmod 700 "$OUT_DIR"
}

db() { # db SQL -> 输出（走 -tA，便于比对）
    docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T "$SERVICE_DB" \
        psql -U "$DB_USER" -d "$1" -tA -c "$2"
}

cmd="${1:-}"; shift || true
parse "$@"

case "$cmd" in
    backup)
        ts=$(date +%Y%m%d-%H%M%S)
        dump="$OUT_DIR/agentflow-$ts.dump"
        docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T "$SERVICE_DB" \
            pg_dump -U "$DB_USER" -Fc --no-owner --no-privileges "$SOURCE_DB" > "$dump"
        chmod 600 "$dump"
        bytes=$(wc -c < "$dump" | tr -d ' ')
        [[ "$bytes" -gt 0 ]] || err "dump 为 0 字节，备份不可信"
        echo "备份完成: $dump ($bytes 字节)"
        # 轮转：BSD ls/head 不支持负数计数，也不能用 mapfile（本机 bash 3.2）——先数再删最老的
        files=$(ls -1 "$OUT_DIR"/agentflow-*.dump 2>/dev/null | sort || true)
        n=$(printf '%s\n' "$files" | grep -c . || true)
        if [ "${n:-0}" -gt "$KEEP" ]; then
            printf '%s\n' "$files" | head -n "$((n - KEEP))" | while IFS= read -r f; do
                [[ -n "$f" ]] && { rm -f "$f"; echo "轮转删除: $f"; }
            done
        fi
        ;;
    verify)
        [[ -f "$FILE" ]] || err "verify 需要一个 dump 文件：bash $0 verify <FILE>"
        docker compose -f "$COMPOSE_DIR/docker-compose.yml" exec -T -i "$SERVICE_DB" \
            pg_restore --list < "$FILE" > /dev/null
        echo "dump 结构可读: $FILE"
        ;;
    drill)
        if [[ -z "$FILE" ]]; then
            FILE=$(ls -1 "$OUT_DIR"/agentflow-*.dump 2>/dev/null | sort | tail -1 || true)
            [[ -n "$FILE" ]] || err "$OUT_DIR 下没有 dump，先跑 backup"
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
        [[ "$fail" == 0 ]] || err "计数不一致：备份不可用于恢复"
        echo "恢复演练通过：备份可用于整库恢复"
        ;;
    *)
        cat <<"USAGE"
用法:
  bash scripts/backup-postgres.sh backup [--compose DIR] [--keep N] [--out DIR]
  bash scripts/backup-postgres.sh verify <FILE>
  bash scripts/backup-postgres.sh drill  [--compose DIR] [--file FILE] [--out DIR]
USAGE
        exit 2
        ;;
esac
