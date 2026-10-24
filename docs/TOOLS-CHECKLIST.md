# 工具与环境清单

> 原则：本清单只列**需要你自己安装/准备**的东西，代理不代下载。✅=本机已验证存在，📥=需你安装，☁️=云端/服务侧准备（无需本机下载）。
>
> ⚠️ 本文原有"本机开发（台式机）"一栏是 2026-10-18 那台 Windows 台式机的记录，与现在这台 Mac 不符，
> 已降级为历史参考。**当前生效的是下面"本机开发（macOS，M1 Pro）"这张表**，逐项跑过命令核过版本。
> 运行时依赖的全景（谁必须给 key、缺了会怎样、rerank 在哪一侧）在 `README.md` §2「三方依赖全景」。

## 本机开发（macOS / Apple M1 Pro，2026-10-24 14:40 实测）

| 工具 | 版本要求 | 状态 | 实测值与位置 |
|---|---|---|---|
| Git | ≥ 2.40 | ✅ | 2.39.5 不满足，但 Apple CLT 只有这个版本；实际只用来提交，CI 在 Linux 上跑，不影响交付。**推送必须用 SSH 形态的 remote**：`~/.ssh/config` 把 `github.com` 映到 `ssh.github.com:443`，而 https 形态在这台机器上不通（git 不读 macOS 系统代理）。检查：`git remote -v` 若显示 https 就 `git remote set-url origin git@github.com:<owner>/<repo>.git` |
| JDK 21 | 21.x | ✅ | `21.0.12.1` @ `~/tools/jdk-21.0.12.1+1/Contents/Home`。**注意 `~/.zshrc` 把 `JAVA_HOME` 默认设成 JDK 8（Zulu）**，跑 `mvn` 前必须显式 export 到 21，否则 javac 8 编 `record` 会报"需要 class, interface 或 enum"这种看着像源码坏掉的假错 |
| JDK 8 | 只给老项目用 | ✅ | Zulu 8.0.504 @ `~/tools/zulu8.96.0.205-...`（Apple Silicon 无 Temurin 8 构建，Zulu 是唯一原生来源） |
| Maven | ≥ 3.9 | ✅ | 3.10.0 @ `~/tools/apache-maven-3.10.0`。本地仓**不在 `~/.m2`**：`conf/settings.xml` 指向 `/Users/a/m2/repository`（约 496MB，spring-ai/spring-cloud 全量已缓存）。`mvn -o` 对 spring-ai-agent 会报 `present, but unavailable`——那是 3.10 的 `_remote.repositories` 归属校验，**不是缺依赖**，联网走已配的阿里镜像即可（实测单个 pom 0.3s） |
| Node.js | ≥ 20 LTS | ✅ | v22.20.0 @ `~/tools/node22/bin/node`（不在 agent shell 的 PATH 上，用绝对路径） |
| pnpm | ≥ 9（可选） | 📥 未装 | 前端实际用 **npm**（仓库里是 `package-lock.json`，`node_modules` 已就位）；不装 pnpm 不影响构建 |
| Docker Desktop | 最新稳定 | ✅ | App **4.96.1** / 引擎 **29.9.2**。**macOS 没有 GPU 直通**（`docker info` 里没有 nvidia runtime），所以 Mac 上跑 GPU 精排走的是宿主机 llama.cpp + Metal，不是容器（见 `SpringCloudAdmin/rerank-apple-gpu/`）。CLI 不在 PATH：`~/.docker/bin`；且 **不会自启**（`AutoStart=false` + 登录项 disabled），重启后要 `open -a Docker` |
| Homebrew | — | ⛔ 不装 | 装它要交互 sudo，本机 `sudo` 一律要密码 ⇒ 全部走 `~/tools` 用户态 tarball 安装 |

## 本机已自建的运行时依赖（不需要你准备）

| 依赖 | 本机形态 | 端口 | 用途 |
|---|---|---|---|
| PostgreSQL 16 + pgvector | `pgvector/pgvector:pg16` 容器（compose 内 `agent-flow-db`，另有独立 `postgres`） | 5432 | prod profile 主数据源，容器形态已实机复验 |
| MinIO | `minio/minio` 容器 | 9000/9001 | 成品对象存储远端副本，**本地已按生产同形态打开并验过**（同一条代码路径，换云只换值） |
| TEI CPU | `rerank-mac` 容器 | 8088 | rag-kb 精排的 CPU 档 |
| llama.cpp Metal | 宿主机 `llama-server --reranking -ngl 99`（b11836 + `bge-reranker-v2-m3` Q4_K_M） | 8089 | rag-kb 精排的 **GPU 档**，实测比 TEI CPU 快 16 倍 |
| Milvus / Qdrant / ES | 各自容器 | 19530 / 6333 / 9200 | rag-kb 可选向量库；agent-flow **不连**这几个 |

> 反向提醒：`docker-compose/` 目录下的 Nacos / Redis / MySQL / RabbitMQ / RocketMQ / Seata / Sentinel / Kafka
> 是 `SpringCloudAdmin` 自己的微服务演示栈，**agent-flow 运行期一个都不连**（Nacos 原本是传递依赖，P7-3#12 已排除）。
> 起环境时别按目录名挨个 `up`。

## 本机开发（台式机，历史记录，已过期）

| 工具 | 版本要求 | 状态 | 用途 |
|---|---|---|---|
| Git | ≥ 2.40 | ✅ 2.49.0 | 版本管理 |
| JDK | 21.x | ✅ 21.0.7 | 后端编译运行（虚拟线程依赖 21） |
| Maven | ≥ 3.9 | ✅ 3.9.10 | 构建；该机的 `~/.m2/settings.xml` 未配镜像，直连 Central 可用。**这台 Mac 不适用**：见上面 settings.xml 与 `/Users/a/m2/repository` 两条 |
| Node.js | ≥ 20 LTS | ✅ v24.11.0 | Vue 前端构建（P4 起） |
| Docker Desktop | 最新稳定（WSL2 后端） | 📥 未安装 | 本地跑 PostgreSQL+pgvector / Redis、验证 compose（P1 起需要） |
| pnpm | ≥ 9（可选） | 📥 可选 | 前端包管理；不用则退回 npm |

## 云侧 / 服务侧（生产）

| 项 | 规格建议 | 状态 | 说明 |
|---|---|---|---|
| 云服务器 | 2C4G 起步（100 QPS 峰值够用），4C8G 稳妥，系统 Ubuntu 22.04/24.04，装 Docker + compose | ☁️ 待准备 | 本机+穿透只能做联调演示，不能承载 10w 用户 |
| PostgreSQL | 16 + pgvector 扩展（自托管容器或云数据库 RDS 均可） | 🟡 本机已有容器，云上待准备 | P1 定连接串口径；选型上优先带 pgvector 的（后续 RAG 场景免二次装） |
| 对象存储（S3 兼容） | OSS / 自建 MinIO 均可 | 🟡 本机 MinIO 已跑通，云桶待交接 | 需要交接的六项见 `README.md`「走云桶需要交接的六项」 |
| Redis | 7.x | ☁️ 暂缓 | `-redis` profile 才需要，单体验证撑住 100 QPS 前不引入 |
| 域名 + HTTPS 证书 | 任意常规域名 | ☁️ 上线前 | 公众号/回调、平台对外接口都需要 |
| 内网穿透（仅联调期） | cloudflared（免费版即可）或自建 frp | ☁️/📥 二选一 | 云上不选，本机若需被外部回调联调再装 client |
| Git 远端 | GitHub（Actions 免费跑 CI）或 Gitee | ✅ 已用 | `.github/workflows/ci.yml` 已入库，push 即生效；**本机推送只用 SSH 形态 remote** |

## 密钥与账号（只给 env 变量名，值自行申请，不写进任何提交）

| 环境变量 | 来源 | 用于 |
|---|---|---|
| `AGENTFLOW_MASTER_KEY` | 本机 `openssl rand -hex 16` 生成 | 凭证库 AES-GCM 主密钥（P3） |
| `AGENTFLOW_API_TOKEN` | 自定强随机串 | 服务通道 token（`X-API-Token`）。P3 起业务身份改用 JWT；P7-1 起该主体 uid=0/role=ops，**不再是全站 admin**，只能碰 uid=0 名下资源 |
| `AI_API_KEY`（`BAILIAN_API_KEY` 兼容保留） | 阿里云百炼控制台（你已有） | LLM 节点：qwen 系列（dashscope 原生，`SpringAiLlmGateway`） |
| 万相（复用 `AI_API_KEY`） | 同上，需开通万相服务 | 生图节点异步任务 `/api/v1/services/aigc/text2image/image-synthesis` |
| `WECHAT_APPID` / `WECHAT_SECRET` | 公众号后台 | 草稿发布节点；注意 IP 白名单要加**实际调用方的出口 IP**（本机联调 = 本机出口 IP，上云 = 云服务器出口 IP） |
| 小红书 token（可选） | 开放平台，走凭证库 `credentialId` | 只有接 `xhs-open-api` 才需要；默认 `xhs-local-package` 只出本地图文包，零外部依赖 |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 你的 PG 实例 | prod profile 数据源（P1 起） |
| `AGENTFLOW_STORAGE_*` + AK/SK 文件 | 云桶控制台 | 成品远端副本；AK/SK **只落 `secrets/storage_{access,secret}_key` 600 文件**，绝不写进 yml/compose，也不贴进对话 |

