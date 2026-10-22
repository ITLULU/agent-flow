#!/bin/sh
# 把 *_FILE 秘密解析成对应明文环境变量后 exec JVM。
# Spring Boot 不认 *_FILE，而 compose secrets 只能以文件形式挂载进容器 —— 这层垫片让密钥
# 永远不进 compose 明文、不进命令行、不进 docker inspect。
# 白名单而非通配 *_FILE：容器里可能有与本项目无关的 *_FILE 变量，通配会误伤启动，
# 也可能把任意变量灌成 JVM 的环境变量。
set -eu

resolve() { # resolve VAR_NAME
    file_var="${1}_FILE"
    file=$(printenv "$file_var" || true)
    [ -n "$file" ] || return 0            # 未声明该 *_FILE：保持原样（可能走明文 env 或本地关闭）
    if [ ! -f "$file" ]; then
        echo "FATAL: $file_var 指向的文件不存在: $file" >&2
        exit 1
    fi
    value=$(cat "$file")
    if [ -z "$value" ]; then
        # 空文件 = 该秘密未配置（compose 允许占位空文件，如百炼/微信 key）。
        # 这里不 FATAL：与"空凭证不阻塞启动、执行期才报配置错"的应用口径一致；
        # 真正必须的 jwt-secret/master-key 缺失时由 StartupSelfCheck 与凭证 API 负责 fail-fast。
        echo "WARN: 秘密文件为空，按未配置处理: $file" >&2
        unset "$file_var"
        return 0
    fi
    export "$1=$value"
    unset "$file_var"
    # 只打印变量名，绝不打印值
    echo "secret resolved: $1 <= $file_var"
}

for name in DB_PASSWORD DB_USER DB_URL \
            AGENTFLOW_JWT_SECRET AGENTFLOW_MASTER_KEY AGENTFLOW_API_TOKEN \
            AGENTFLOW_BOOTSTRAP_ADMIN_PASSWORD \
            BAILIAN_API_KEY WECHAT_APPID WECHAT_SECRET; do
    resolve "$name"
done

exec "$@"
