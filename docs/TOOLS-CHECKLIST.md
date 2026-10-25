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
| Node.js | ≥ 20 LTS | ✅ | node **v22.20.0** / npm **10.9.3** @ `~/tools/node22/bin/`。`~/.zshrc:35` 有 `export PATH="$HOME/tools/node22/bin:$PATH"`，**新开终端能直接用**（证据：`/bin/zsh -ic 'command -v node'` ⇒ `/Users/a/tools/node22/bin/node`）。⚠️ 但**内嵌的非交互 shell 不保证**：凡在 2026-10-25 11:45 之前抓过环境快照的进程（本仓库里跑的那个 agent shell 就是），PATH 里没有 `node22/bin`，`command -v node` 依旧找不到 —— 别据此断言"这台机器没装 Node"。同一条原因还让**绝对路径调 npm** 失败：`npm` 的 shebang 是 `#!/usr/bin/env node`，所以 `~/tools/node22/bin/npm -v` 报 `env: node: No such file or directory`；在缺 PATH 的 shell 里必须先补：<br>`export PATH="$HOME/tools/node22/bin:$PATH"`<br>本机 `/usr/local/bin`、`/opt/homebrew/bin`、`~/.nvm` 三处都没有第二个 node（逐个 `ls` 验过；别用带通配的 `ls`，zsh 通配无匹配会中止整行造成假阴性），所以别指望"系统里那个 node" |
| pnpm | ≥ 9（可选） | 📥 未装 | 前端实际用 **npm**：仓库里是 `package-lock.json`，`node_modules` 30 个顶层包与 `package.json` 声明（`vue` + `@logicflow/core` + `vite` + `@vitejs/plugin-vue`）匹配，**没有** vue-router/pinia/element-plus —— 那些本就不是本项目依赖，别误判成"装漏了" |
| Docker Desktop | 最新稳定 | ✅ | App **4.96.1** / 引擎 **29.9.2**。**macOS 没有 GPU 直通**（`docker info` 里没有 nvidia runtime），所以 Mac 上跑 GPU 精排走的是宿主机 llama.cpp + Metal，不是容器（见 `SpringCloudAdmin/rerank-apple-gpu/`）。CLI 不在 PATH：`~/.docker/bin`；且 **不会自启**（`AutoStart=false` + 登录项 disabled），重启后要 `open -a Docker` |
| Homebrew | — | ⛔ 不装 | 装它要交互 sudo，本机 `sudo` 一律要密码 ⇒ 全部走 `~/tools` 用户态 tarball 安装 |

### 前端构建的可复现性（2026-10-25 11:36 实测）

`cd web/agent-flow-web && export PATH="$HOME/tools/node22/bin:$PATH" && npm run build`
⇒ `✓ 784 modules transformed`、`✓ built in 1.23s`。注意 **vite 的 outDir 不是 `dist/`**，而是直接
产出到 `server/agent-flow-server/server-application/src/main/resources/static/`（`index.html` + `assets/index-*.css|js`），
由 Spring Boot 当静态资源伺服 —— 所以 `ls dist` 一定是空的，那不是失败。
重建后的文件名哈希与仓库里**已提交的产物完全一致**（`git status` 干净），即"提交的静态资源 = 本机这条命令的输出"可复现。

### 本机 IPv6 是关的（会影响所有 localhost 联调）

`networksetup -getinfo Wi-Fi` ⇒ **IPv6: Off**。后果：`/etc/hosts` 里 `localhost` 同时映射 `127.0.0.1` 与 `::1`，而 Node 17+ 解析 `localhost` 时 **v6 优先**，于是"绑 localhost"的服务（vite 默认行为）只监听 `[::1]`，本机任何客户端连它都失败，curl 报的错还特别不像网络错：`connect to ::1 ... failed: Exec format error`。

- 判定命令：`lsof -nP -iTCP:<port> -sTCP:LISTEN` 看 `NAME` 是 `127.0.0.1:x` 还是 `[::1]:x`。
- 口径：**凡是自己起的服务都显式绑 `127.0.0.1`**（`vite.config.js` 已这么写，代理目标同理）；`java`/Spring Boot 绑 `*:8080` 属双栈，v4 能连（`http://127.0.0.1:8080/api/ping` 200）、v6 连不上（`http://[::1]:8080` 直接失败），这是正常现象别去"修"。
- 同理，`docker ps` 会把映射端口列成 `0.0.0.0:15432` **和** `[::]:15432` 两行，但那只是 docker 的写法：实测 `127.0.0.1:15432` 通、`[::1]:15432` 连不上。**任何"端口明明映射了却连不上"，先换成 `127.0.0.1` 再怀疑应用。**（2026-10-25 16:01 起 `agent-flow-db` 的映射已显式写成 `127.0.0.1:15432:5432`，`docker ps` 只剩一行；上面这条记录的是当时用临时 override、未指定 `host_ip` 时 docker 的显示形态。）

## 本机已自建的运行时依赖（不需要你准备）

| 依赖 | 本机形态 | 端口 | 用途 |
|---|---|---|---|
| PostgreSQL 16 + pgvector | `pgvector/pgvector:pg16` 容器（compose 内 `agent-flow-db`，另有独立 `postgres`） | 容器网内 5432；宿主 `127.0.0.1:15432`（compose 已发布，只绑回环） | **默认 profile 与 prod 的同一份主数据源**（2026-10-25 16:40 实机复验：默认 profile 无参数启动即连 15432）。**起后端前必须先 `up -d agent-flow-db`** |
| MinIO | `minio/minio` 容器 | 9000/9001 | 成品对象存储远端副本，**本地已按生产同形态打开并验过**（同一条代码路径，换云只换值） |
| TEI CPU | `rerank-mac` 容器 | 8088 | rag-kb 精排的 CPU 档 |
| llama.cpp Metal | 宿主机 `llama-server --reranking -ngl 99`（b11836 + `bge-reranker-v2-m3` Q4_K_M） | 8089 | rag-kb 精排的 **GPU 档**，实测比 TEI CPU 快 16 倍 |
| Milvus / Qdrant / ES | 各自容器 | 19530 / 6333 / 9200 | rag-kb 可选向量库；agent-flow **不连**这几个 |

> 反向提醒：`docker-compose/` 目录下的 Nacos / Redis / MySQL / RabbitMQ / RocketMQ / Seata / Sentinel / Kafka
> 是 `SpringCloudAdmin` 自己的微服务演示栈，**agent-flow 运行期一个都不连**（Nacos 原本是传递依赖，P7-3#12 已排除）。
> 起环境时别按目录名挨个 `up`。

## 依赖下载来源与全局路径（2026-10-25 12:05 实测）

| 生态 | 配置读的是哪一份 | 落地/缓存位置 | 下载来源 |
|---|---|---|---|
| Maven | **全局 = `~/tools/apache-maven-3.10.0/conf/settings.xml`**（`mvn -X validate` 打印 `Global preference: ...` 可复核）；用户级 `~/.m2/settings.xml` **不存在** | 本地仓 `/Users/a/m2/repository`（由上面文件的 `<localRepository>` 指定） | 阿里 `https://maven.aliyun.com/repository/public`（`<mirrorOf>central</mirrorOf>`） |
| npm | 四级（builtin / `$prefix/etc/npmrc` / `~/.npmrc` / 项目 `.npmrc`）**全部不存在** ⇒ `npm config list` 只有 `default values` | `prefix=/Users/a/tools/node22`；全局包 `…/lib/node_modules`（只有 npm、corepack）；全局 bin `…/bin`；缓存 `~/.npm`（113MB） | 见下面 lockfile 一条：现有依赖走镜像，但**配置里没存镜像** |
| Docker | `~/.docker/daemon.json` | 镜像层在 VM 里，宿主侧文件 `~/Library/Containers/com.docker.docker/Data/vms/0/data/Docker.raw` | `docker.m.daocloud.io` / `docker.1ms.run` / `docker.xuanyuan.me` |

> ⚠️ `/Users/a/m2/settings.xml` 与 Maven `conf/settings.xml` 内容逐字节相同，但**没有任何东西读它** —— 改它不会生效，要改就改 `conf/` 那份。同理，`node_modules` 装得上不代表 registry 配好了。

### npm registry：要不要 `npm config set registry https://registry.npmmirror.com`

- **现有 88 个依赖本来就走镜像**：`web/agent-flow-web/package-lock.json` 里 88 条 `resolved` 全是 `registry.npmmirror.com`（且 88 条都带 `sha512` `integrity`），文件已提交在 `459f44b`。所以 `npm ci` / 重装 `node_modules` **不受 registry 配置影响**，改成 npmjs 也不会重下。
- **但 registry 从未落盘**：`npm config get registry` 返回 `https://registry.npmjs.org/`（因为四级 npmrc 都不存在）。⇒ 只有"新增包"和"将来重写 lockfile"这一步会先打 npmjs。
- **实测延迟**（口径：完整拉一次 `vite` 的 packument（约 39MB），各 3 次采样；走的是**直连**——curl 不读 macOS 系统代理，且采样时 127.0.0.1:7897 并未监听。不是统计结论）。命令：
  `for i in 1 2 3; do curl -s -o /dev/null -w '%{time_total}s %{size_download}B\n' https://registry.npmjs.org/vite; done`（镜像域名同理）
  ⇒ npmjs `7.80 / 8.24 / 8.31 s`，npmmirror `3.09 / 3.34 / 3.62 s`，镜像快 2 倍以上。注意这个包元数据特别大，小包差距会小些。
- 结论：**建议设**（`npm config set registry https://registry.npmmirror.com`，写 `~/.npmrc`），它只影响解析、不改变已锁定包的字节（integrity 校验兜底）；代价是这份 lockfile 已经把**所有消费者（包括将来放开的 CI）绑到一个第三方镜像**上——镜像若不可用，`npm ci` 会一起失败，这属于可接受的已知耦合，但要心里有数。

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
| `WX_API_APPID` / `WX_API_SECRET` | 公众号后台 | 草稿发布节点（旧名 `WECHAT_APPID/WECHAT_SECRET` 仍兼容，新名优先）。**IP 白名单要加实际调用方的出口 IP**（本机联调 = 本机出口 IP，上云 = 云服务器出口 IP）；实测 secret 填错直接回 `errcode=40125 invalid appsecret`→A0400 不可重试，不会静默 |
| 小红书 token（可选） | 开放平台，走凭证库 `credentialId` | 只有接 `xhs-open-api` 才需要；默认 `xhs-local-package` 只出本地图文包，零外部依赖 |
| `PG_DB_URL` / `PG_DB_USER` / `PG_DB_PASSWORD` | 本机 compose 的 `agent-flow-db`（宿主 15432） | **默认 profile 与 prod 共用的数据源三元组**（2026-10-25 起从 `DB_*` 改名，容器入口脚本 `docker-entrypoint.sh` 的 `*_FILE` 白名单同步）；只有 `PG_DB_PASSWORD` 必须给 |
| `AGENTFLOW_STORAGE_*` + AK/SK 文件 | 云桶控制台 | 成品远端副本；AK/SK **只落 `secrets/storage_{access,secret}_key` 600 文件**，绝不写进 yml/compose，也不贴进对话 |

