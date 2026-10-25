# agent-flow

自研 Agent 自动化工作流平台：可视化 DAG 编排（采集 → LLM 写作 → 生图 → 发布），Spring Boot 3.5 / Java 21 模块化单体 + Vue 3 画布。设计目标按生产上线标准（初期 10w 注册用户 / 3w DAU / 峰值 100 QPS），生产部署于云服务器。

> 只想跑起来：**要起哪些容器见 §1.1**（本地默认形态 **1 个容器**：`agent-flow-db`），**后端命令 §1.2、前端命令 §1.3**；容器形态 §1.5 / §5。

## 1. 快速开始

前置：JDK 21、Maven 3.9+、curl + jq；改前端另需 Node.js 20+（逐项版本见 `docs/TOOLS-CHECKLIST.md`，
"哪些是必须装 / 哪些必须给 key / 缺了会怎样"见 §2 的**三方依赖全景**）。**默认 profile 的数据源就是 PostgreSQL，本地与线上同一条代码路径**：起服务前必须先把 `agent-flow-db` 容器拉起来并注入 `PG_DB_PASSWORD`（见 §1.2）。唯一不需要任何外部中间件的是 `mvn verify`——单测自带 `jdbc:h2:mem`，与运行时数据源无关。
本服务只连四类外部东西：数据源、LLM+生图（DashScope）、公众号、对象存储；**精排 rerank 不在本服务**，它属于检索侧 `spring-ai-agent/rag-kb`。

### 1.1 先选形态：到底要起几个容器

| 形态 | 要起的容器 | 数据源 | 什么时候用 |
|---|---|---|---|
| **A 本地默认 profile** | **1 个**：`agent-flow-db`（compose 已发布 `127.0.0.1:15432`） | 默认 profile = **PostgreSQL 16**（`application.yml` base 段），`security.enabled: false` | 改代码、跑创作链路、画布开发——**这就是日常形态**，与线上共用同一份数据源定义 |
| **B 本地进程 + 生产口径鉴权** | **1 个**：`agent-flow-db` | 同一份 PG + prod profile 叠加 `security.enabled: true` | 要连鉴权/密钥注入一起验 |
| **C 容器形态（生产同构）** | **2 个**：`agent-flow-db` + `agent-flow-server`；打开成品对象存储副本时再 **+1 个** `minio` | compose 注入的 `agent-flow-db:5432` | 交付、演示、按生产口径复验 |

数据源在 2026-10-25 从「H2 文件库」改成了「默认即 PG」（见 `docs/PROGRESS.md` 当日条目）：**A′/pg profile 已随之删除**，不再有"本地走文件库、线上走 PG"两套 SQL 路径。H2 只留在测试里（`server-application/pom.xml` 已把它降为 `test` scope，fat jar 内不含 H2 驱动）。

**明确不需要为 agent-flow 启动的容器**：`SpringCloudAdmin/docker-compose/` 下的 nacos、redis、mysql、rabbitmq、rocketmq(+dashboard)、kafka(+ui)、zookeeper、seata、sentinel、es(+kibana)、milvus、qdrant、rerank/rerank-gpu/rerank-mac，以及**独立的 `postgres`**（那个是 rag 侧 `rag` 库用的，本服务用的是 `agent-flow-db`）—— 这些要么是 `SpringCloudAdmin` 自己的微服务演示栈，要么是 rag-kb 的检索基建。**照目录名挨个 `up` 是最常见的一次误判**（Nacos 原本只是传递依赖，P7-3#12 已排除；rerank 本服务根本不连）。

> 本机 Docker Desktop **不会自启**（`AutoStart=false` + 登录项 disabled），重启电脑后要先 `open -a Docker`，等 `docker info` 能通再起容器；`docker` CLI 在 `~/.docker/bin`。

### 1.2 后端启动（形态 A：默认 profile + 真 PG）

**先起数据库，再起进程**——默认 profile 连的就是 `127.0.0.1:15432` 的 `agent-flow-db`：

```bash
cd /Users/a/source/SpringCloudAdmin/docker-compose/agent-flow
docker network inspect my-custom-network >/dev/null 2>&1 || bash ../network.sh   # 网络必须先存在
docker compose up -d agent-flow-db                                                # 等 STATUS=healthy
```

```bash
export JAVA_HOME=/Users/a/tools/jdk-21.0.12.1+1/Contents/Home   # ~/.zshrc 默认把 JAVA_HOME 指到 JDK 8，不切就编不过
export PG_DB_PASSWORD="$(cat /Users/a/source/SpringCloudAdmin/docker-compose/agent-flow/secrets/db_password)"
cd server/agent-flow-server
mvn -B install -DskipTests            # 先把 4 个模块装进本地仓库（单模块 spring-boot:run 依赖它）
mvn -B verify                          # 全模块编译 + 单测/集成测试（测试自带 jdbc:h2:mem，不连容器）
mvn -pl server-application spring-boot:run     # 启动（默认 profile，无需 --spring.profiles.active）
# 或直接跑打包产物：java -jar server-application/target/server-application-0.1.0-SNAPSHOT.jar
```

`PG_DB_URL` / `PG_DB_USER` 有默认值（`127.0.0.1:15432/agentflow`、`agentflow`），**只有 `PG_DB_PASSWORD` 必须注入**。缺它不会静默连到别的库：Flyway 在 bean 创建阶段就失败，日志 `The server requested SCRAM-based authentication, but no password was provided`，进程 `exit 1`，端口根本不绑（实测见下）。

IntelliJ 里等价改法：Run Config 的 Environment variables 加 `PG_DB_PASSWORD=<secrets/db_password 的内容>`，**Program arguments 不需要任何 profile 参数**（2026-10-25 之前那行 `--spring.profiles.active=pg` 已作废，profile `pg` 已删除）。

验证（2026-10-25 16:40 实测，1 容器）：

```bash
curl -s http://localhost:8080/actuator/health/liveness   # {"status":"UP"}
curl -s http://localhost:8080/api/ping                   # {"code":"0",...,"data":"pong"}
```

实测输出：`Database: jdbc:postgresql://127.0.0.1:15432/agentflow (PostgreSQL 16.15)`、`active profiles : []`、自检 `datasource : jdbc:postgresql://127.0.0.1:15432/agentflow` + `security.enabled: false`、`Started AgentFlowApplication in 2.508s`；`readiness` UP、`/api/ping` `pong`、跑一条创作链路后 `psql` 查到 `workflow=1 execution=1 artifact=1 node_log=2`（写路径确认落在 PG）。
反向验：`env -u PG_DB_PASSWORD` 启动 → `exit=1`、8082 零监听、`data/agentflow.mv.db` 一个都没生成。

⚠️ **同一条命令在不同形态下结论不同，别据此误判服务坏了**：`security.enabled=true` 时（形态 B/C 的 prod）`/api/ping` 与 `/api/workflows` 都需要身份，**无 token 返回 401 是设计如此**——`EndpointSensitivityContractTest.java:42` 把 `GET /api/ping` 钉成 `USER` 级；`/actuator/health/liveness|readiness` 仍可匿名读。刚重启的机器上如果 8080 连不上，先确认 `agent-flow-db` 在不在（进程和库都不在时，日志第一条失败就是 Flyway 连接异常，不是端口问题）。

### 1.3 前端启动

后端起来后直接访问 `http://localhost:8080/` 即可用（打包产物已随 `server-application/src/main/resources/static` 入库，**前端不需要单独起服务，也不需要 nginx**）。只有要改前端时才用本地 dev server：

```bash
cd web/agent-flow-web
npm install
npm run dev      # 前端 http://localhost:5173，/api 代理到 http://localhost:8080（VITE_API_TARGET 可改）
npm run build    # 直接输出到 server-application/src/main/resources/static（注意：不是 dist/）
```

端口对照：**8080 = 后端 API + 打包后的画布静态页**，**5173 = vite dev 的画布**（改前端时用，代理到 8080）。`npm run build` 的产物目录是 Spring Boot 的 `resources/static`，所以 `ls dist` 必然为空，那不是失败。

生图有**独立前端项目** `web/agent-image-studio`（与画布应用互不依赖）：`cd web/agent-image-studio && npm install && npm run dev` → `http://127.0.0.1:5174/`。它只做一件事——参考图定风格、内容换自己的、按渠道（小红书主图/配图、公众号主图/章节图）自动出对应尺寸，勾"四宫格"时同时给拼图和 4 张单图。两个项目共用同一份 `af_token`（localStorage）与同一个后端 `/api/image/studio/*`，登录一次两边都通。

⚠️ **"进程在、端口在听，浏览器却无法访问"这一类先查协议族**：vite 默认 `host: 'localhost'`，而 Node 会把 `localhost` 解析成 `::1` 优先 ⇒ dev server 只绑 IPv6 回环。本机 Wi-Fi 的 **IPv6 是 Off**（`networksetup -getinfo Wi-Fi | grep -i ipv6`），任何 v6 连接直接失败，于是 5173 看着活着却谁都连不上。`vite.config.js` 已显式写 `server.host: '127.0.0.1'`（代理目标同理写 `127.0.0.1:8080`，否则页面能开而 `/api` 全挂）；**不要用 `host: true`**，那会绑 `0.0.0.0` 把 dev server 暴露到局域网。自检三行：

```bash
lsof -nP -iTCP:5173 -sTCP:LISTEN            # NAME 应是 127.0.0.1:5173；出现 [::1]:5173 就是绑错了族
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5173/     # 200 才算通
curl -s http://localhost:5173/api/ping                              # pong = 前端→后端代理链路也通
```

### 1.4 端口发布与形态 B（本地进程 + prod 鉴权）

`agent-flow-db` 自带 `ports: 127.0.0.1:${AGENTFLOW_DB_HOST_PORT:-15432}:5432`（2026-10-25 16:01 起）。**只绑 127.0.0.1**：这个库的口令是 `secrets/db_password`，绑 `0.0.0.0` 等于把 PG 开放给整个局域网；不占 5432 是因为同网络的独立 `postgres` 服务用它，两个 compose 同时 `up` 会撞端口。要换宿主端口：`AGENTFLOW_DB_HOST_PORT=25432 docker compose up -d agent-flow-db`（改了默认值，`PG_DB_URL` 也要跟着改）。

**本地客户端连库**（DBeaver / IDEA Database / psql）：Host `127.0.0.1`、Port `15432`、Database `agentflow`、User `agentflow`、Password 取 `secrets/db_password`。容器之间仍走服务名 `agent-flow-db:5432`，不绕宿主端口。

**B（连鉴权一起验）**：prod profile 只叠加 `security.enabled=true`（数据源定义与默认完全相同，见 `application.yml` 文件头），需再给 `AGENTFLOW_JWT_SECRET` / `AGENTFLOW_MASTER_KEY`，此后所有 `/api/**` 无 token 返回 401 是设计如此：

```bash
export PG_DB_PASSWORD="$(cat secrets/db_password)"
export AGENTFLOW_JWT_SECRET="$(cat secrets/jwt_secret)" AGENTFLOW_MASTER_KEY="$(cat secrets/master_key)"
java -jar .../server-application-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

B 的既有实测（2026-10-25 12:28，当时靠 `/tmp/override-devdb.yml` 发布端口，现由 compose 自带）：`Started ... in 2.523s`、`security.enabled: true`、`readiness` UP、public 9 张表、`flyway_schema_history` 全 `success=t`。

明文 env **只为本地验证**；容器/生产一律走 `*_FILE` secret（见 §5 与 compose 注释），密钥不进 yml、不进命令行、不进 `docker inspect`。

### 1.5 形态 C：整条链路都进容器

三个命令起手，完整口径（秘密文件、bootstrap 管理员、密钥轮转、健康核对）看第 5 节：

```bash
cd /Users/a/source/agent-flow/server/agent-flow-server
DOCKER_BUILDKIT=1 docker build -t agentflow/server:0.1.0 .          # 改了 Java 代码才需要重建
cd /Users/a/source/SpringCloudAdmin/docker-compose/agent-flow
bash gen-secrets.sh                                                  # 首跑：700/600，已被 .gitignore 覆盖
docker compose up -d                                                 # 起 agent-flow-db + agent-flow-server
docker compose ps                                                    # 两个都应是 healthy
```

成品对象存储副本默认**关**（`AGENTFLOW_STORAGE_ENABLED=false`，关时一个字节也不出网）；要在本地开就起 `docker-compose/minio`（9000/9001）并把 endpoint 写 `http://minio:9000`、AK/SK 落 secret 文件。

跑一条真实创作链路（需注入 `AI_API_KEY`；缺 key 时启动正常、`copy.write` 执行报 A0400 配置错）：

```bash
cd /path/to/agent-flow
scripts/run-creation.sh --mode xhs --topic "秋天适合把书一本本读完" --ratio 3:4
scripts/run-creation.sh --mode wechat --url https://mp.weixin.qq.com/s/xxxx
```

用法、DSL 拆解与成品库 API 见 `docs/CREATION-GUIDE.md`。

### 1.6 前端画布能力（P4）

启动命令与端口口径见 §1.3（`npm run dev` = 5173 代理到 8080；`npm run build` 直出 `resources/static`，前端无独立容器）。

画布支持：登录/注册、工作流列表与新建、节点拖拽/连线、检查器改参、保存（升版本）、cron 调度、运行与节点时间线、产物抽屉。`vite.config.js` 的 `base: './'` 让静态资源按相对路径加载，挂在任意前缀下都能用。

生产形态（云服务器）：compose 维护在 `SpringCloudAdmin/docker-compose/agent-flow`（与中间件同一网络），镜像在本仓库 `docker build` 后由 compose 引用，密钥只走 secret 文件，见第 5 节。

## 2. 配置参考表

| 键 | 默认值 | 修改时机 |
|---|---|---|
| `server.port` | `8080` | 端口冲突时 |
| `server.shutdown` | `graceful` | 不要改：上线口径排空在途请求 |
| `spring.lifecycle.timeout-per-shutdown-phase` | `20s` | 长工作流节点变多后调大 |
| `server.error.include-stacktrace` | `never` | 不要改：异常不出栈双保险 |
| `agentflow.security.enabled` | 本地 `false` / prod `true` | 对外提供服务的任何环境必须 `true`（yml=代码=文档三处一致） |
| `agentflow.security.api-token` | `${AGENTFLOW_API_TOKEN:}`（空） | 仅环境变量注入，禁止写死进 yml；P3 后作为服务间调用通道（X-API-Token），恒定时间比较。**P7-1 起该通道主体是 uid=0 / role=ops，不是全站管理员**：它只能读写 uid=0 名下的资源，碰他人工作流/凭证一律 404（评审 #2 修复）。用它跑创作脚本时，产出的工作流与成品都归属 uid=0 |
| `agentflow.security.metrics-token` | `${AGENTFLOW_METRICS_TOKEN:}`（空） | **Prometheus 抓取专用身份（P7-2c）**：只对 `GET /actuator/prometheus` 生效，支持 `Authorization: Bearer <token>` 或 `X-Metrics-Token`；同一把 token 调业务 API 一律 401。未配置时该端点保持 401（安全默认）。容器部署走 `AGENTFLOW_METRICS_TOKEN_FILE=/run/secrets/metrics_token`，`gen-secrets.sh` 自动随机生成 |
| `agentflow.security.jwt-secret` | `${AGENTFLOW_JWT_SECRET:}`（空） | env 注入 ≥32B；enabled=true 且未配 → 启动即失败（fail-fast）；prod 必配 |
| `agentflow.security.master-key` | `${AGENTFLOW_MASTER_KEY:}`（空） | env 注入 base64(32B)；凭证库 AES-GCM 主密钥；未配时凭证 API 报 A0400，其余功能不受影响。新密文封装为 `v2:{kid}:base64(iv‖ct)`，启动日志只回显 kid 指纹，便于核对生效密钥 |
| `agentflow.security.master-key-previous` | `${AGENTFLOW_MASTER_KEY_PREVIOUS:}`（空） | **轮换窗口专用（P7-3#16）**：旧主密钥只解密不加密；为空=不启用双读。旧密文在首次被业务读取时自动用新主密钥重封装，全部迁移完成后即可清空此配置 |
| `agentflow.security.token-ttl` | `2h` | JWT 有效期；prod 可改短，改后需同步前端刷新策略 |
| `agentflow.security.rate-limit-per-minute` | `120` | 登录用户（per-uid）与服务通道（独立 `svc` 桶）各自的每分钟固定窗上限；`0`=关掉整套限流；多实例部署时需改网关/Redis 计数（已记账） |
| `agentflow.security.rate-limit-auth-per-minute` | `30` | `/api/auth/**`（注册/登录）单独阈值，**按客户端 IP 分桶**。拆桶原因：原先匿名与服务通道同挤 uid=0 一个桶，刷登录接口就能把两者一起打满（评审 P6 #6） |
| `agentflow.security.trust-forwarded-for` | `false` | 打开后才用 `X-Forwarded-For` 第一段当客户端 IP。**只有前面那层反代会覆盖该头时才能开**，否则攻击者伪造 XFF 即可绕开 IP 桶；不开时全部来源都算反代 IP，auth 桶会退化成单桶 |
| `agentflow.schedule.tick-ms` | `15000` | cron 扫描节拍；测试/多实例调大避免抢发 |
| `spring.datasource.*`（默认 profile = 线上同一份） | `${PG_DB_URL:jdbc:postgresql://127.0.0.1:15432/agentflow}` / `${PG_DB_USER:agentflow}` / `${PG_DB_PASSWORD:}` | **本地与 prod 共用这一份定义，prod 段不再重复声明数据源**。`PG_DB_URL`/`PG_DB_USER` 的默认值只把"连哪个实例"外置，不把"用哪种库"外置；口令刻意留空默认——解不出来就在启动时报错（Flyway SCRAM 失败、进程 exit 1），好过连上后再吃一个 28P01。云服务器用三个 env 覆盖，禁止写死进 yml |
| `spring.datasource.hikari.maximum-pool-size`（默认与 prod 共用） | `20` | 多实例部署时按总连接÷PG max_connections 重摊。硬约束式：**实例数 × pool-size ≤ `max_connections` × 0.8**（PG 默认 100 ⇒ 2 实例×20 有余量，5 实例×20 就打满，第 6 个实例连接排队）。配额（#11）管的是同时跑几条执行，管不了连接池总量；要突破这个天花板要么调 PG `max_connections`，要么上 PgBouncer（本机未部署，属十万档） |
| `spring.flyway.enabled` | `true` | 不要改：schema 只允许迁移文件演进 |
| `management.endpoint.health.probes.enabled` | `true` | 不要改：liveness/readiness 分离是 compose 健康检查依赖 |
| `AGENTFLOW_MASTER_KEY`（环境变量） | 未设置 | 已在 P3 接入（yml 映射到 `agentflow.security.master-key`）；生产上线前必须注入 base64(32B) 主密钥 |
| `AGENTFLOW_MASTER_KEY_PREVIOUS`（环境变量） | 未设置 | 轮换期旧主密钥（base64 32B）；容器走 `AGENTFLOW_MASTER_KEY_PREVIOUS_FILE`，空文件按未配置处理。窗口结束必须清空，否则旧密钥的泄露面一直存在 |
| `agentflow.nodes.llm.api-key` / `image.api-key` | `${AI_API_KEY:${BAILIAN_API_KEY:}}`（空） | env 注入（`AI_API_KEY` 为统一入口，`BAILIAN_API_KEY` 保留兼容；容器走 `AI_API_KEY_FILE`）；空凭证不阻塞启动，`copy.write`/`llm.chat`/`image.cover`/`image.wanxiang` 执行时报不可重试配置错 |
| `agentflow.nodes.llm.default-model` | `qwen-plus` | 节点 params.model 可逐次覆盖（多模型可配置） |
| `agentflow.nodes.image.base-url` | `https://dashscope.aliyuncs.com` | 仅测试/代理环境改；节点 `ratio`/`size` 决定出图尺寸 |
| `agentflow.nodes.wechat.app-id` / `app-secret` | `${WX_API_APPID:${WECHAT_APPID:}}` / `${WX_API_SECRET:${WECHAT_SECRET:}}` | env 注入（新名 `WX_API_*` 优先，旧名兼容；容器走 `WECHAT_*_FILE` secret）；云端需将出网 IP 加进公众号 IP 白名单；单用户可用 `wechat.draft.credentialId` 走凭证库 |
| `agentflow.content.library-dir` | `${AGENTFLOW_LIBRARY_DIR:./data/library}` | 成品库根目录，所有读写被约束在此目录内（`resolveInside` 防穿越）；容器/云主机必须 env 指向持久卷，否则重启丢成品。启动自检做写探针 |
| `agentflow.content.storage.enabled` | `${AGENTFLOW_STORAGE_ENABLED:false}` | **成品远端副本开关（P7-3#12b）**。关闭时不建客户端、不排扫描、一个字节也不出网；打开时端点/桶/凭证必须齐全，否则启动即失败（fail-fast） |
| `agentflow.content.storage.endpoint` / `bucket` / `region` / `key-prefix` | `${AGENTFLOW_STORAGE_ENDPOINT:}` / `${AGENTFLOW_STORAGE_BUCKET:}` / `us-east-1` / `artifacts` | S3 兼容端点（MinIO 同网络填 `http://minio:9000`），path-style 访问；`key-prefix` 决定对象键前缀 |
| `agentflow.content.storage.access-key` / `secret-key` | `${AGENTFLOW_STORAGE_ACCESS_KEY:}` / `${AGENTFLOW_STORAGE_SECRET_KEY:}`（空） | **只从环境变量或 `*_FILE` 秘密文件注入**，永不写进 yml/compose；错误报文只含 HTTP 状态与 S3 `<Code>`，绝不回显对端报文（防凭证经错误信息外泄） |
| `agentflow.content.storage.connect-timeout-ms` / `request-timeout-ms` | `5000` / `30000` | 出网超时；PUT 带 `x-amz-content-sha256`，超时/半途断连都会落到 `sync_state=failed` 并留原因，不会静默留半套远端副本 |
| `agentflow.content.storage.sweep-interval-ms` / `sweep-initial-delay-ms` | `60000` / `15000` | 后台补扫节拍与首轮延迟；每轮最多 20 条，单条失败只记状态不影响其余。测试与容量调优时先调大 initial-delay，避免后台线程抢走本该显式验证的同步 |
| `agentflow.exec.max-concurrent` | `${AGENTFLOW_EXEC_MAX_CONCURRENT:16}` | 单实例同时执行的工作流数（P7-3#10 外置）；按节点真实耗时压测后再调，改配置重启即生效，无需重建镜像 |
| `agentflow.exec.max-queued` | `${AGENTFLOW_EXEC_MAX_QUEUED:64}` | 允许等待执行槽的条数；超过即同步 `429/A0429`（不创建执行记录），避免无上限静默积压。`0` = 只跑不排 |
| `agentflow.exec.acquire-timeout` | `${AGENTFLOW_EXEC_ACQUIRE_TIMEOUT:5m}` | 排队等槽上限；超时执行落 `FAILED` 并释放指标 `agentflow.execution.rejected{reason="acquire_timeout"}`，不留永远 `RUNNING` 的记录 |
| `agentflow.exec.global-quota.enabled` | `${AGENTFLOW_EXEC_GLOBAL_QUOTA_ENABLED:false}` | **跨实例全局执行配额（P7-3#11）**。关闭时本地 `max-concurrent` 就是全局上限（单实例形态，零额外依赖）；开启后"谁在跑"记在共享库 `execution_lease`，N 个实例看同一本账。**fail-closed**：配额表缺失（V7 未迁移）时执行直接失败，不会静默退化成各数各的 |
| `agentflow.exec.global-quota.max-concurrent` | `${AGENTFLOW_EXEC_GLOBAL_QUOTA_MAX_CONCURRENT:}`（启用时必填） | **全集群**同时在跑的执行数上限，不是每实例上限。启用时留空 / `<1` / 与单用户上限矛盾 ⇒ 启动即失败 |
| `agentflow.exec.global-quota.per-user-max-concurrent` | `${AGENTFLOW_EXEC_GLOBAL_QUOTA_PER_USER_MAX_CONCURRENT:0}` | 单个归属人（`workflow.owner_id`）在集群里最多同时跑几条；`0` = 只受全局上限约束。cron 触发的执行同样算在属主头上 |
| `agentflow.exec.global-quota.lease-ttl` / `.renew-interval` | `10m` / `30s` | 租约 TTL 防实例崩溃不还名额（到期由下一次预留顺带回收），续租节拍防"活着的长执行被当成崩溃残留"。`renew-interval` 必须落在 `(0, lease-ttl)`，否则启动失败——那正是上限被静默突破的配置 |
| `agentflow.exec.global-quota.reserve-retry-interval` | `1s` | 拿到本地槽后等集群名额的重试节拍；最长等待复用 `acquire-timeout`，等不到落 `FAILED` + `rejected{reason="global_quota_timeout"}` |
| `agentflow.resilience.enabled` | `${AGENTFLOW_RESILIENCE_ENABLED:true}` | **下游依赖舱壁/熔断总开关（P7-3#17）**；关闭后节点直连（仅限排障，生产保持开启） |
| `agentflow.resilience.dependencies.<name>.max-concurrent` | llm 8 / image 4 / wechat 4 / xhs 4 / web 8 | 每个依赖独立的进程内并发上限；满舱壁立即 `C0004`（不排队、不打下游），防止一个慢依赖占满全部执行槽 |
| `agentflow.resilience.dependencies.<name>.failure-threshold` | llm/image 5，wechat/xhs 3，web 10 | 连续"依赖侧失败"（仅 C0001 连接拒绝 / C0002 超时 / C0003 协议错误）达到阈值即熔断；A04xx 等客户端错误视为对端可达并重置计数 |
| `agentflow.resilience.dependencies.<name>.open-duration` | 30s（wechat/xhs 60s） | 熔断冷却时长；到期后放行 1 个半开探测，成功闭合、失败重新 open。open 期间节点直接 `C0004`，引擎判不可重试 |
| `agentflow.engine.max-parallel-nodes` | `${AGENTFLOW_ENGINE_MAX_PARALLEL_NODES:8}` | 同一条执行内"同一层（互不依赖）节点"的并发上限（P7-3#18 并行支路）。调大不会让单节点变快，只会让依赖抖动时更容易打穿舱壁；与 `exec.max-concurrent` 相乘才是进程内节点任务的上界。`<1` 启动即失败 |

### 走云桶需要交接的六项（自测阶段不需要，本地 MinIO 已跑通同一条代码路径）

远端副本这一面**已经在本机以生产形态 compose + 真 MinIO 打开并验过**（`.env` 里
`AGENTFLOW_STORAGE_ENABLED=true`、endpoint 指 `http://minio:9000`）。换云桶只是换下面六个值，
代码路径、编码口径、失败语义完全同一套——所以下面任何一项都不需要"先写代码"。

| # | 要提供的东西 | 放进哪里 | 说明 |
|---|---|---|---|
| 1 | 端点（含 scheme） | `.env` 的 `AGENTFLOW_STORAGE_ENDPOINT` | 例：`https://oss-cn-beijing.aliyuncs.com`；必须是本机能直连的地址。走 path-style，阿里 OSS 需确认桶允许 path-style |
| 2 | 桶名 | `.env` 的 `AGENTFLOW_STORAGE_BUCKET` | 桶**建议已存在**；不存在时启动会尝试建桶，建桶失败不致命（记 `bucket_unavailable` 指标并继续提供服务），但同步会一直失败 |
| 3 | AccessKey | `secrets/storage_access_key`（600，gitignore 内） | 只走文件。**不要贴进对话、不要写进 yml/compose** |
| 4 | SecretKey | `secrets/storage_secret_key`（600，gitignore 内） | 同上 |
| 5 | region | `.env` 的 `AGENTFLOW_STORAGE_REGION` | 默认 `us-east-1`；MinIO 可留默认，云厂商按控制台标注填（SigV4 参与签名，填错是 403 而不是"能连通"） |
| 6 | key-prefix（可选） | `.env` 的 `AGENTFLOW_STORAGE_KEY_PREFIX` | 默认 `artifacts`；同桶多环境共用时才需要改 |

权限要求：该 AK 需要对桶的 `PutObject`/`GetObject`/`HeadObject`/`DeleteObject`，以及（若桶可能不存在）`CreateBucket`。
只需要"备份上去、出事能取回"的话，可以给到最小集：前四项 + region，key-prefix 留默认。

交接方式（二选一，都避免密钥进对话）：
- 直接把两个 secret 文件写好，再 `docker compose up -d agent-flow-server`；
- 或者只给 AK/SK 的**名称/位置**（如"在密码管理器里叫 xxx"），由本机侧落文件。

打开后怎么确认它真的在工作（两条命令，不必读代码）：

```bash
# ① 启动装配：有这行说明客户端与后台扫描都建起来了
docker -H unix://$HOME/.docker/run/docker.sock logs agent-flow-server | grep 成品对象存储就绪
# ② 反向验证：故意让备份失败，看状态是否落到 failed 且带上原因（而不是静默成功）
```

注意 fail-fast 的边界：`enabled=true` 而 1/2/3/4 任一为空 ⇒ **进程启动即退出**，这是设计而非故障；
但"端点连不通"不会让进程退出（只记 ERROR 与 `bucket_unavailable` 指标），因为备份面挂掉不该拖垮创作主链路。

### 三方依赖全景：谁必须装、谁必须给 key、缺了会怎样

先划一条最容易读错的线：**`docker-compose/` 目录下那一堆中间件不等于本服务需要它们。**
agent-flow 运行期只连四类东西：数据源、LLM/生图、公众号、对象存储。Nacos / Redis / MySQL /
RocketMQ / Seata / Sentinel / Kafka 都是 `SpringCloudAdmin` 自己的微服务演示栈
（Nacos 原先还是传递依赖，P7-3#12 已排除，见 `docs/PROGRESS.md`）。给本服务装环境时按下面这张表来，
别按目录名挨个 up。

| 依赖 | 具体用途（代码位置） | 本地能不能自给 | 缺了会怎样 |
|---|---|---|---|
| **DashScope（通义 qwen）** | `llm` 节点与创作文案，`SpringAiLlmGateway` 走 `com.alibaba.cloud.ai.dashscope`；默认模型 `qwen-plus`，节点可用 `params.model` 覆盖（application.yml:58-60） | ❌ 不能本地替代，只能给 key | 凭证空**不阻塞启动**，节点照常注册；执行时报不可重试的配置错误。舱壁 `llm: max-concurrent 8`（:117） |
| **万相生图（同 DashScope 账号）** | `image.*` 节点，`DashScopeImageClient` 异步任务：`/api/v1/services/aigc/text2image/image-synthesis` + `/api/v1/tasks/{id}` | ❌ 同上 | 同上；舱壁 `image: max-concurrent 4` |
| **微信公众号开放接口** | 草稿/素材发布：`cgi-bin/token`、`cgi-bin/material/add_material`、`cgi-bin/draft/add`（application.yml:64-67） | ❌ 需要公众号 app-id/secret，且**调用方出口 IP 要加白名单** | 空凭证不阻塞启动；`wechat.draft` 执行期失败。本地用 `publish.local` 出成品包代替，不碰平台 |
| **小红书** | `xhs.draft` 默认只生**本地图文包**（`channel=xhs-local-package`）；接开放平台才需 `credentialId` 从凭证库取 token（`XhsDraftNodeExecutor.java:30`） | ✅ 默认路径零外部依赖 | 不接开放平台不影响交付物；接了则凭证缺失/越权时报错，舱壁 `xhs: max-concurrent 4` |
| **热点榜四个公开源** | `hotspot.fetch` 默认 `weibo,baidu,toutiao,zhihu`（`HotTopicFetchNodeExecutor.java:69-86`），单源失败进 `failedSources` 并**让本节点自报 DEGRADED**（部分源失败不再记 SUCCESS） | ⚠️ 半可：节点支持 `endpointOverride` 换成自建/桩地址，换源不改代码 | 全挂则本次无热点，不影响其他节点。注意这四个是**公开网页接口，无 SLA、可能改字段或反爬**，别当生产依赖记账 |
| **PostgreSQL 16 + pgvector** | **默认 profile 与 prod 共用同一份数据源**（`application.yml:12` base 段，prod 段只叠鉴权）；H2 只存在于测试作用域（`server-application/pom.xml` test scope，fat jar 不含） | ✅ 本地 `pgvector/pgvector:pg16` 容器，compose 把它发布成 `127.0.0.1:15432` | **起服务前必须先 `docker compose up -d agent-flow-db`**：连不上 PG 就不绑端口、进程 exit 1，readiness 也无从谈起 |
| **S3 兼容对象存储** | 成品远端副本，`AGENTFLOW_STORAGE_*`（:76-86） | ✅ 本地 MinIO（`http://minio:9000`）已按生产同形态验过 | `enabled=false` 时一个字节不出网、也不建客户端；`enabled=true` 但配置不齐 = **启动即退出**（故意的 fail-fast） |
| **成品库目录** | 所有产物读写被约束在此目录内（`JdbcContentStore.resolveInside`，:71） | ✅ 默认 `./data/library` | 目录不可写 ⇒ 启动自检打 ERROR，`publish.local`/`xhs.draft` 执行失败（`StartupSelfCheck.java:104`） |
| **JDK 21 / Maven 3.10 / Node 22** | 编译运行（虚拟线程需 21）、Vue `^3.5.44` + Vite 5.4.21 前端 | ✅ 三者都在 `~/tools` 下 | 低版本 JDK 编译直接失败；注意 `~/.zshrc` 默认把 `JAVA_HOME` 指向 JDK 8，跑 mvn 前要显式切 21。Node 已在 `~/.zshrc:35` 进 PATH，**新开终端可直接 `node -v`**；但环境快照早于该时刻的进程（如内嵌的 agent shell）里 `node` 仍不可见，此时**用绝对路径调 `npm` 会因 `env: node: No such file or directory` 失败**（npm 的 shebang 是 `#!/usr/bin/env node`），需先 `export PATH="$HOME/tools/node22/bin:$PATH"` |
| **Docker Desktop** | 容器形态复验与 compose 起停（本机 4.96.1 / 引擎 29.9.2） | ✅ | **不会自启**（`AutoStart=false` 且登录项 disabled），重启后要手动 `open -a Docker` |
| **GitHub Actions** | CI：`ubuntu-latest` + JDK 21（`.github/workflows/ci.yml:10,20`） | ❌ 远端 | push 后不绿就是没验证过 |

#### 精排（rerank）在哪一侧 —— 别在本服务里找它

`rerank` **不是 agent-flow 的依赖**，全项目 grep 不到；它属于同机的检索服务 **`spring-ai-agent/rag-kb`**
（配置见 `rag-kb/src/main/resources/application.yml:80-82`）。所以"要不要装 rerank"取决于你跑哪一侧：

| 项 | rag-kb 侧口径 |
|---|---|
| 开关 | `RERANK_ENABLED`（默认 `false`：没部署推理服务时主链路不受牵连） |
| 地址 | `RERANK_URL`，默认 `http://localhost:8087/rerank` |
| 契约 | 请求 `{"query","texts"}`；响应两种都吃 —— TEI 裸数组 `[{"index","score"}]` 与托管 API `{"results":[{"index","relevance_score"}]}`（`TeiReranker.java:24-25`） |
| 本地两个选项 | ① TEI CPU 容器 `rerank-mac`（8088）；② **llama.cpp Metal 走 Apple GPU**（`SpringCloudAdmin/rerank-apple-gpu/`，8089）——M1 Pro 实测 20 候选 296ms，同二进制 CPU 档 785ms，TEI CPU 4792ms |
| 分数口径 | llama.cpp 回**未过 sigmoid 的原始 logit（可以是负数）**，TEI 回 0~1 ⇒ **跨后端只能比名次，不能比分数值**。`rerankScore` 全链路只用于排序与展示，无 0~1 阈值判断，所以负分安全；真跑过 `TeiReranker` 本体验证（`RerankLiveCheck.java`，20/40 候选整批返回 + 死端口反向降级） |
| 缺了会怎样 | 静默**降级为融合名次截断** + 一行 WARN，`/api/query` 不会 500。⚠️ 这个降级会伪装成"精排已开"：`enabled=true` 但请求被服务端拒了（批量上限/permit 不够）时，功能上等于没开 —— 判据是返回的 `rerankScore` 是否非 null，不是 HTTP 200 |

rag-kb 侧其余外部依赖：DashScope `qwen-plus`（chat）与 `text-embedding-v3`（**输出 1024 维，
必须显式对齐 `embedding-dimension`，默认 1536 会在首次入库时 dimension mismatch 且建坏 collection**）、
PG chunk store（`application-persistent.yml:5`）、可选 Milvus（本机 v2.4.9 在跑，换 embedding 模型 = 换向量空间 =
必须换 collection 名或清库重灌）、以及 MCP server 对外暴露工具（`/mcp`）。

#### 只有你能为我提供的东西（其余本机都能自建）

`AI_API_KEY`（LLM+生图同源，`BAILIAN_API_KEY` 兼容保留）、`WX_API_APPID`/`WX_API_SECRET`（旧名 `WECHAT_APPID`/`WECHAT_SECRET` 仍兼容；+ 把出口 IP 加白名单）、
云桶的 AK/SK（上表六项）、以及若要接小红书开放平台的 token。
**一律走 env 或 `*_FILE` 秘密文件，不要贴进对话、不要写进 yml/compose**；密钥落文件后 600、目录 700。

**前端包来源**：`package-lock.json` 的 88 条 `resolved` 已全部锁定到 `registry.npmmirror.com`（每条都带 `sha512` integrity），
所以 `npm ci` 与 registry 配置无关；但本机四级 npmrc 都不存在，`npm config get registry` 仍是 npmjs 官方源，**只有新增包会先打官方源**（实测该源在这台机器慢 2 倍以上）。
建议 `npm config set registry https://registry.npmmirror.com` 把来源落盘；镜像/全局路径完整清单见 `docs/TOOLS-CHECKLIST.md`「依赖下载来源与全局路径」。

## 3. 架构与关键决策

- **模块单向依赖**：`server-application → node-adapters → workflow-engine → platform-common`；父 pom 只做版本治理。详见 `docs/IMPLEMENTATION-PLAN.md`。
- **只用 Spring AI（+ Alibaba）做模型层**，不引 LangChain4j——编排层已自研 DAG，LangGraph 类抽象与之正面冲突；且其 Java 侧（langgraph4j）为非官方社区移植，违反"禁单点社区依赖"纪律。2026-10 核查：Spring AI 1.0.x/1.1.x/2.0 三线活跃发版，"停维"传言不成立。
- **模型层可替换缝**：节点只依赖纯 Java 接口 `LlmGateway`，Spring AI 仅是其实现 `SpringAiLlmGateway`——换 SDK 成本封顶在单个实现类，引擎/DSL/日志零改动。
- **编排为自研 DAG 引擎**（JSON DSL + `NodeExecutor` SPI），节点四件套契约：超时/捕获/降级/重试，降级必须带 `degraded=true` 显性标志。
- **超时 = 放弃等待 + 中断，不是"发第二条请求"**：`orTimeout` 只让 future 变异常，引擎会中断仍在跑的工作线程；声明 `hasSideEffects()` 的节点（`wechat.draft` / `xhs.draft` / `image.wanxiang` / `image.cover`）在"结果不可知的超时"后**不再重试**，失败理由固定带一句"副作用结果不可知…请人工核对"。真 HTTP 桩测试 `WechatDraftTimeoutTest` 钉住一次执行只允许 1 个 `draft/add`。
- **规模判断**：100 QPS 峰值 → 模块化单体 + 虚拟线程执行池，不上微服务/消息队列；PG 为生产库（prod profile），本地默认 profile 不依赖任何中间件。
- **traceId 口径**：入口过滤器生成或透传（`X-Trace-Id`），进 MDC、进结构化响应体，请求级 RT 以此为唯一统计口径。
- **创作流水线节点集（P4A）**：`hotspot.fetch`（四源热榜，合并去重、整体不可用降级）→ `article.extract`（链接取正文，自动抽标题）→ `copy.write`（`mode=xiaohongshu|wechat`，按【标记】分节解析出标题/摘要/标签/金句/封面文案；长素材先蒸馏成要点再创作，蒸馏清单明确要求打乱原文条目顺序）→ `image.cover`（比例预设或任意 `a:b`，长边 1024 折算、边长 512–1440；`kind=cover` 主图 / `kind=illustration` 正文配图；失败降级为无图）→ `publish.local`（成品库 `{标题}-{yyyyMMddHHmmss}.md` + `meta.json` + `assets/`）→ `copy.originality`（与原文相似度评分，排在归档之后：它是发布闸门，超阈值时不外发但稿子必已落库）→ `xhs.draft` / `wechat.draft`（发布通道永不静默降级）。
- **交付物形态（P4B，回应"没有排版/没有配图层次/可能照抄"）**：
  - **排版**：`wechat.draft` 的纯文本正文经 `WechatHtmlLayout` 渲染成**全内联 style** 的 HTML（微信编辑器会清洗 `class` 与 `<style>`，只认内联），四套配色 `theme=moss|azure|amber|ink`（未知值 A0400 并列出可选）；小标题带编号徽章与渐变色条，中文序号标题（`一、`/`第三节`）也识别；正文已是 HTML 则原样透传不二次包装。
  - **配图层次**：封面走 `material/add_material`（永久素材，做 `thumb_url`），正文配图走 `media/uploadimg` 换永久 `mmbiz` 直链——草稿箱不代理外链，写万相临时地址等于 2 小时后裂图。超过 1MB 的图**跳过并写进 `warnings`**，不静默丢；一张都传不上去也照样出警告。按小节均匀锚点插图，小节数不足退化到段落锚点。
  - **原创性**：`copy.originality` 输出 `similarityScore`（0–100）+ `riskLevel` + `maxCommonRun` + 四维（措辞/结构/主题/表达）明细。本地口径为字符 5-gram 包含率 + 最长连续公共片段，叠加模型评分后**取更严的一侧**；连续重合 ≥24 字直接抬到 60 分并降级，不给"整体分数低但整段照搬"留缝隙。`strict=true` 时超阈值让链路 FAILED。**模型评分缺失本身就是降级**：`useLlm=true` 却没拿到可解析的评分（网关未配、调用失败、报文里没有 `similarityScore`）时，输出带 `llmNote` 写明是哪一种，整条执行转 `SUCCESS_WITH_DEGRADED`，`strict=true` 则直接阻断发布段——本地 5-gram 看不出"换词照抄"，让"未校验"以 0 分绿着放行是最坏的一种假绿（2026-10-25 执行 16 实测：同一篇参考文，执行 15 模型口径 68/high，执行 16 模型缺位时本地口径 0/low）。只有显式 `useLlm=false`（只要本地口径）才算正常通过。
    实测定了个不漂亮的调子（2026-10-25 18:55，执行 15）：外链改写稿的 `maxCommonRun` 只有 5 字——整句照抄确实被掐掉了——但结构/表达两维仍把总分推到 **68/high**，模型换了说法却继承参考文的"10 条清单 + 对仗句式"。于是两条模板一起收紧：`copy-wechat.txt` 明令"骨架也算抄袭"（条目数、顺序、标题里的数字格式、"不是……而是……"这类标志性句式一律不许沿用），`material-digest.txt` 不许再"保留方法论骨架"，要点必须打乱原序归类并单列"素材的形状"。默认 `strict=false` 只标降级不拦发布，要拦就 `strict=true`。
  - **命名**：`publish.local` 正文文件名固定 `{标题}-{yyyyMMddHHmmss}.md`（`slug` 可覆盖前缀），时间戳与文件名一起进 `meta.json` 和库里的 `meta_json`，`repair` 按同名重建——重跑同一选题不会冲掉上一版稿子。
- **成品库与列表 API**：`GET /api/workflows?limit=&beforeId=`、`GET /api/credentials?limit=&beforeId=`、`GET /api/artifacts?kind=&limit=&beforeId=` 全部为键集分页（默认 50 条、上限 200），响应 `{items,nextCursor}`（`nextCursor=null` 表示末页）；`/{id}`、`/{id}/files`、`/{id}/file?path=`；非属主与不存在同为 404（不泄露"存在但无权"），下载走 `filesOf` 白名单 + `resolveInside` 双重防穿越，MIME 未知一律 `application/octet-stream`（不猜 text/html，防存储型 XSS）。
- **工作流删除是逻辑删除**：`DELETE /api/workflows/{id}`（V9 迁移加 `deleted`/`deleted_time` 两列）。只翻标记并**同时关掉调度、清空 `next_fire_at`**——删掉的流水线继续被 cron 领发是最贵的一类 bug，所以 `fireDue()` 的 SQL 里也再判一次 `deleted = FALSE`（纵深防御，单测把已删行手工重新 armed 来验证这条过滤不是摆设）。隐身是**读路径统一过滤**（`findById`/`list`/`update`/`ownerOf` 都带 `deleted = FALSE`），不是前端自己藏：打开画布、改 DSL、触发执行、重复删除一律 404；重复删除不报"成功"，让调用方分清"我删掉了"和"本来就没有"。库里行、DSL、执行记录、成品目录与磁盘文件全部保留（`execution.workflow_id` 有外键，物理删等于要级联销毁三张表 + 成品文件）。
- **成品完整性核对与补偿（P7-3#12a）**：`artifact.file_state`（pending/complete/incomplete）+ `file_error`（Flyway V5）。`publish.local` 先插行拿 id 才能定目录名，所以"有行"不等于"有货"——落盘任一步失败会把原因钉在行上（`incomplete` + `file_error`）再照常抛错。`GET /api/artifacts/incomplete` 列出半套成品，`POST /api/artifacts/{id}/repair` 用库里的 title/body/meta 按 **`meta.markdownFileName` 原名**重渲染正文与 `meta.json`（渲染口径与落盘口径同源 `ArticleMarkdown`，逐字节一致；存量老成品没有该字段时回退 `article.md`，不造第二份文件），并实测 `assets/*`：**图不在数据库里，缺了照实返回 `missing` 且状态留在 `incomplete`**，补偿绝不把半套数据刷成绿色。指标 `agentflow.artifact.files{state}` / `agentflow.artifact.repair{state}`。注意 `file_state` 是"发布时状态"，不是磁盘实时状态——手工删过的文件不会被 `/incomplete` 看见，用 `/files`（磁盘实况）或 `repair`（实测并重建）核对。

- **成品远端副本同步（P7-3#12b）**：`agentflow.content.storage.*`（默认关闭）。开启后 `publish.local` 落盘成功的成品可推到任意 S3 兼容端点（MinIO/OSS/AWS），键为 `{key-prefix}/{artifactId}/{相对路径}`。手动 `POST /api/artifacts/{id}/sync`，后台 `sweep` 每轮补 20 条；只同步 `file_state=complete` 的成品（半套不上远端，否则"本地丢了还能 repair"变成"远端也错了"）。**同步永不改 `file_state`**，失败只写 `sync_state=failed` + `sync_error`——备份面坏了却说"稿子没了"是最坏的误导。成功时把 `{相对路径 → sha256}` 清单落库：它既是本地目录被清空后的下载白名单（`GET /{id}/file?path=` 回落到远端字节，先比对 sha256，不一致宁可 404 也不交付坏副本），也是"远端确实有货"的证据。签名是纯 JDK 的 AWS SigV4（`SigV4`，不引 SDK），path-style、`Redirect.NEVER`、错误报文只带 HTTP 状态与 S3 `<Code>`（不回显对端 body，防凭证经错误信息外泄）。指标 `agentflow.artifact.sync{state=synced|failed|skipped|served|bucket_unavailable}`。

- **跨实例全局执行配额（P7-3#11）**：`agentflow.exec.global-quota.*`（默认关闭）。执行闸门是进程内 `Semaphore`，多实例就是 `16×N` 条链路同时打向同一个 PG 与同一批下游——**名额记在共享库 `execution_lease` 里，N 个实例看同一本账**。仲裁段"`SELECT … FOR UPDATE` 哨兵行 → 回收过期租约 → 数名额 → 插租约"在一个事务内完成；少了那把行锁，两个实例会在同一瞬间各自数出"还剩一个名额"（反向验证实测：去掉 `FOR UPDATE` 后，上限 4 的集群同一时刻跑起了 10 条）。口径：一条租约覆盖**引擎真正执行的这段时间**（拿到本地槽之后、收尾之前），所以 `max-concurrent` 读作"全集群同时在跑"，本地排队不占额度。三道防线：① 提交时只读预检，集群满了同步 `429/A0429` 且不建执行记录；② 拿到本地槽后真正预留，等不到名额落 `FAILED`（`rejected{reason="global_quota_timeout"}`），与"等不到本地槽"同口径，不留永远 `RUNNING`；③ 心跳续租 + 过期回收：崩溃实例的租约由下一次预留顺带删掉（否则一次 OOMKill 就永久吃掉一份全局容量），而活着的长执行靠续租保住名额；租约被回收时**不重新抢占**，只记 `agentflow.exec.quota{kind=lost}`——重抢会让同一执行占两份名额。优雅停机把本机名额立刻交回，不等 TTL。指标 `agentflow.exec.global.reserved`（抓取时查库，报集群实况；DB 抖动时该值为 NaN 而不是让整个指标页 500）/ `.capacity` / `.waiting`（本机正在等集群名额的执行数）。**状态口径**：执行记录的 `RUNNING` 含"已受理但还在等本地槽或集群名额"这一段，所以集群跑满时 `RUNNING` 会大于真正在跑的数量——用 `exec.global.reserved`（集群实况）与 `exec.global.waiting`（等待数）区分"在跑"和"在等"，`execution.queue.wait` 直方图含这两段等待。真实形态复验（两台容器实例共用一个 PG16，scratch 库，`global-cap=2`、本地 `max-concurrent=8`）：一次并发提交 8 条（2 条在 curl 侧连接失败，实际受理 6 条），租约峰值恒为 2 且分布在两个实例 id 上，6 条执行耗时呈 8/8/16/16/24/24s（2 宽的门把排队时间原样摊进墙钟）；对照组关掉配额时同一批并发实测 6 条同时在跑、`execution_lease` 零行。**同步拒绝**面：预算被 2 条占住后再提交 6 条，4 条当场 `429/A0429`（"集群执行配额已满（在跑 2 条）"）且不建执行记录，另 2 条在预检与预留之间挤进去排队。**不做的事**：限流仍是进程内固定窗（跨实例要网关或 Redis）、舱壁/熔断仍各自计数、cron 领发仍靠 `next_fire_at` CAS。

## 4. 已知限制（延期台账）

> **当前形态口径**：本项目按**单作者/受信小团队自用**交付与验证。首轮专家评审给出对外多用户形态的
> **NO-GO** 决议（🛑 4 项：凭证跨租户可读、`X-API-Token` 即全站 admin、无任何 metrics、DB 宕机时 readiness 仍报 UP），
> 完整算式与证据见 [docs/EXPERT-REVIEW-P6.md](docs/EXPERT-REVIEW-P6.md)。**四条 🛑 已于 P7-1 修完并实机反向验证**，
> 🔶 5 项（密钥 fail-fast / 匿名限流桶 / TIMEOUT 重复发布 / 抢注即接管 / 备份演练）已于 P7-2 修复；
> **对外仍 NO-GO** 的剩余理由：真异地副本缺云端桶凭证（#9 的定时/产物归档/告警与 #12b
> 的成品远端副本已于 P7-3 接上，代码与挂点齐了、本机 MinIO 实测通过，缺的是云桶凭证与生产启用）、
> 微信真凭证外发未验、跨实例限流/舱壁计数/同步令牌仍为进程内（多实例部署要靠网关或 Redis）。
> 主密钥轮换（#16）、下游舱壁/熔断（#17）、成品完整性补偿（#12a）、
> 跨实例全局执行配额（#11，含两台容器实例共用一个 PG16 的实机复验）已于 P7-3 完成；
> #12a/#12b/#18 三条功能回路另于 2026-10-23 在临时容器实例 + 真 PG16 + 真 MinIO 上复跑通过（含反向验证）。

| 项 | 状态 | 兑现节点 |
|---|---|---|
| ~~凭证 `credentialId` 无归属校验（他人密钥可被引用发草稿）~~ | ✅ P7-1 已修（端口按 workflow 属主校验，非属主与不存在同为不可用；实机验证"他人引用同一 id 时本地桩一次请求都没收到"） | - |
| ~~无 Prometheus/metrics 端点 + readiness 组不含 db（探针说谎）~~ | ✅ P7-1 已修（5 项业务指标 + readiness `include: readinessState,db,ping`；实机库不可达 liveness 200 / readiness 503） | - |
| ~~metrics 的容器形态 + 真 PG16 复验~~ | ✅ 2026-10-22 已复验（重建镜像后在 pgvector/pg16 + prod profile 上 28 项断言全绿；scratch 库验证后销毁，prod 库仍 0 用户/0 工作流） | - |
| Prometheus 抓取身份 | ✅ P7-2c 已修：新增 `AGENTFLOW_METRICS_TOKEN`（仅解锁 `/actuator/prometheus`，同 token 调业务 API 仍 401）；未配置时端点保持 401。同时修掉 `/actuator/health/../prometheus` 借原始前缀白名单绕过鉴权的路径 | - |
| 密钥 fail-fast 晚于端口绑定；prod 关鉴权只 log | 🔶 见评审 #5 | P7-2（0.5 人日） |
| 匿名请求共享 uid=0 限流桶（登录全局 120/min） | 🔶 见评审 #6 | P7-2 |
| 执行判 TIMEOUT 后节点仍继续跑并真实发布（重复草稿） | 🔶 见评审 #7 | P7-2（1 人日） |
| ~~执行闸门 `Semaphore(16)` 硬编码、排队无超时~~ | ✅ P7-3#10 已修：`agentflow.exec.*` 运行期可配（默认 16 槽 / 排队 64 / 等槽 5m），排队满同步 429，超时执行落 FAILED；容器实测 env 覆盖与非法值 fail-fast | - |
| ~~主密钥无 kid/轮换流程（换密钥后旧凭证全废）~~ | ✅ P7-3#16 已修：primary+previous 双读单写，新密文带 kid，旧密文首次读取自动重封装；容器实测轮换前凭证执行成功、清空 previous 后仍成功 | - |
| ~~下游无熔断/舱壁，一个慢依赖可占满执行槽~~ | ✅ P7-3#17 已修：`DependencyGuard` 在节点注册处按依赖（llm/image/wechat/xhs/web）包舱壁+熔断；连续 C0001–C0003 触发 open，期间 `C0004` 快速失败且不打下游，冷却后单探测恢复；A04xx 不算依赖失败。指标 `agentflow.dependency.state/trips/rejected`，真实 HTTP 桩 E2E 验证 open 期间下游零请求 | - |
| ~~成品库"DB 有行、盘上没货"无补偿入口~~ | ✅ P7-3#12a 已修：`file_state`/`file_error` 落库 + `/api/artifacts/incomplete` 核对 + `/api/artifacts/{id}/repair` 补偿（文本按同源渲染逐字节重建，缺图如实报缺并保持 incomplete）；`ArtifactIntegrityE2ETests` 4 条含反向验证（删文件后下载真 404、缺图补偿不翻绿、非属主 repair 404、未启用存储时同步入口如实回"未启用"）；**容器形态已复验**（临时实例 + 真 PG16：删盘后 `/file` 真 404、`/incomplete` 如实为空、`repair` 后 sha256 逐字节相同） | - |
| ~~成品只落本地卷，换实例/重建卷即丢~~ | ✅ P7-3#12b 已修：可选 S3 兼容远端副本（SigV4 纯 JDK 实现），`POST /api/artifacts/{id}/sync` + 后台补扫；下载在本地缺失时按 sha256 清单回落到远端，校验不一致宁可 404。真 MinIO 实测往返与"错密钥必被 403 拒"，另 7 条 E2E 覆盖写失败/PUT 成功但对象不在/远端被改坏/补扫自愈/归属隔离/关闭态如实报未启用；**容器形态已复验**（临时实例 + 真 MinIO：整目录删除后 `/file` 从远端逐字节取回且不落盘；SigV4 直投坏副本 ⇒ `/file` 404、`sync_state=failed` + `sync_error="远端副本校验不一致"`、`file_state` 不受牵连；清掉坏对象后 repair+sync 自愈） | - |
| 多实例：限流进程内固定窗 / 舱壁与熔断各自计数 / 同步无分布式令牌 | 🟡 单实例假设（执行额度面已由 #11 解除：`global-quota` 打开即全集群一份账，双容器实机复验；其余仍需网关/Redis） | 十万档（评审 §5） |
| ~~无全局执行配额，多实例即 `16×N` 打同一个 PG（评审 #11）~~ | ✅ P7-3#11 已修：`execution_lease` + 哨兵行 `FOR UPDATE` 仲裁、租约 TTL + 心跳续租 + 过期回收、单用户上限、预检 429 / 等不到落 FAILED、停机归还名额。**fail-closed**：配额表缺失时执行失败而不是放开上限。11 条单测 + 2 条真实 HTTP E2E；**反向验证**：去掉 `FOR UPDATE` 后上限 4 的集群同时跑起 10 条（测试当场变红）；真 PG16 实测行锁排队 2.35s、过期回收、按实例作用域的释放不误删他人租约；**两台容器实例共用一个 PG16 实机复验**：`cap=2` 时租约峰值 2 且分布在两个实例、6 条并发耗时摊成 8/8/16/16/24/24s，关配额对照组 6 条同时在跑，预算占满后新提交 4/6 当场 `429/A0429` 且不入库 | - |
| ~~DAG 引擎/DSL 校验/执行落库/触发 API~~ | ✅ P1 已交付（见 docs/PROGRESS.md） | - |
| ~~PG 真实实例联调（schema/连接池实机验证）~~ | ✅ P5-4 已交付（pgvector/pg16 容器实机：Flyway 3 迁移全成功、Hikari 连接池 + 2.6s 起服、执行与调度落库、重启数据保留，见 docs/PROGRESS.md） | - |
| ~~cron 定时触发（目前仅手动 API 触发）~~ | ✅ P3 已交付（next_fire_at CAS 领发 + 节拍参数化，见 docs/PROGRESS.md） | - |
| ~~并行分支执行（v1 拓扑序串行）~~ | ✅ P7-3#18 已修：引擎改为按依赖分层，同层节点并发（宽度 `agentflow.engine.max-parallel-nodes`，默认 8），层末汇合即止损点；支路失败 ⇒ 下游零放行、同层兄弟不抢占取消、结果仍按拓扑位序返回。`WorkflowEngineParallelTest` 5 条含反向对照（宽度=1 时菱形屏障必然失败，证明"并发"不是假绿）；**容器形态已复验**（同一镜像两种宽度对照：宽度 8 ⇒ 菱形两条支路墙钟 3.27s、下游桩实测并发峰值 2；宽度 1 ⇒ 6.51s、峰值 1） | - |
| ~~LLM/生图/采集/发布节点~~ | ✅ P2 已交付（http.collect/llm.chat/image.wanxiang/wechat.draft，见 docs/PROGRESS.md） | - |
| ~~创作流水线闭环（热榜/链接/话题 → 双渠道文案 → 指定比例主图 → 成品库 → 草稿）~~ | ✅ P4A 已交付（hotspot.fetch/article.extract/copy.write/image.cover/publish.local/xhs.draft/wechat.draft + `/api/artifacts*`，`P4CreationPipelineE2ETests` 两条通道端到端，见 docs/CREATION-GUIDE.md） | - |
| 百炼真实链路 | ✅ key 已注入并实测（`AI_API_KEY` → DashScope `qwen-plus` HTTP 200）；公众号/小红书草稿外发仍需 `WECHAT_*` 真凭证后跑 `scripts/run-creation.sh` | 用户执行 |
| 小红书**官方草稿 API** | 平台无公开草稿接口：默认只产出图文包落成品库，配 `xhs.draft.endpoint` 才外发；上线第三方 MCN 通道需重评 | P4 后评估 |
| ~~JWT 用户体系、凭证 AES-GCM 加密~~ | ✅ P3 已交付（纯 JDK HS256/PBKDF2/AES-GCM；登录态刷新策略归 P4 前端） | - |
| 多实例横向扩展：限流内存固定窗、cron 领发无分布式锁 | 单实例假设已钉死（台账 P3 决策），多实例前必解 | P5/容量阶段 |
| ~~Vue 画布（LogicFlow）~~ | ✅ P4 已交付（`web/agent-flow-web`，Vue 3 + LogicFlow 2.2；登录/列表/拖拽/保存/调度/执行时间线/产物，构建直出 Spring Boot `static/`） | - |
| ~~Dockerfile / compose 中 server 服务~~ | ✅ P5 已交付（多阶段镜像 + 非 root + `read_only`/`cap_drop ALL`/`no-new-privileges` + `*_FILE` 秘密垫片）。compose 已并到 `SpringCloudAdmin/docker-compose/agent-flow`（唯一一套，my-custom-network），见第 5 节 | - |
| nacos 传递依赖（spring-ai-alibaba 带入） | ✅ P7-3#12 收敛：已在 `spring-ai-alibaba-core` 上排除 `spring-alibaba-nacos-config`（健康指标曾把 `/actuator/health` 拉成 DOWN），启动日志 nacos/ERROR 为 0。**`nacos-client` 无法移除**：`DashScopeApi` 构造期调用其 `StringUtils`，排除即 `NoClassDefFoundError`；fat jar 仍含该 7.8MB（上游依赖，等上游修复） | 已收敛 |
| 管理员只由 env 注入的 bootstrap 账号产生（`AGENTFLOW_BOOTSTRAP_ADMIN_USERNAME/PASSWORD`，P7-2a） | 注册接口永不授予 admin；留空则系统无管理员，需先配置再重启 | ✅ 已收敛 |
| ~~CI 实际执行~~ | ✅ 已生效：`459f44b` 的 [run 39880053665](https://github.com/ITLULU/agent-flow/actions/runs/39880053665) `completed success`（全模块 `mvn -B verify`） | - |
| 本机内网穿透仅用于联调演示 | 10w 用户量级必须云服务器部署（4C8G 起） | 上线前 |

## 5. 生产部署（compose，实机已验）

**唯一一套 compose 维护在 `SpringCloudAdmin/docker-compose/agent-flow/docker-compose.yml`**（与 mysql/qdrant/nacos 同目录风格、同挂 `my-custom-network`）。该文件自包含：只消费镜像 `agentflow/server:0.1.0`，秘密与数据都在 compose 目录下，不引用本仓库任何路径。

两个服务：`agent-flow-db`（pgvector/pg16，宿主侧只绑 `127.0.0.1:15432`——既是本地进程的默认连接目标，也给 DBeaver/psql 连库；容器之间仍走 `agent-flow-db:5432`）+ `agent-flow-server`（prod profile，8080）。数据用目录挂载（compose 目录下 `data/pg`、`data/library`），容器重建不丢。

```bash
# 1) 在本仓库构建镜像（改了 Java 代码才需要重跑）
cd /path/to/agent-flow
DOCKER_BUILDKIT=1 docker build -t agentflow/server:0.1.0 server/agent-flow-server

# 2) 首跑生成秘密，然后起服务
cd /path/to/SpringCloudAdmin/docker-compose/agent-flow
bash gen-secrets.sh                 # 幂等生成 secrets/（目录 700、文件 600、.gitignore 已覆盖）
docker compose up -d
docker compose ps                   # 两个都 healthy 才算起完（server 健康检查用 readiness 探针）
```

`gen-secrets.sh` 必配：db_password / jwt_secret(≥32B) / master_key(base64 32B) / metrics_token（脚本自动随机）；可空：ai_api_key / wechat_appid / wechat_secret —— 空文件按"未配置"处理，启动不受阻，对应节点执行时才报 A0400 配置错。要跑真实创作链路就在 `secrets/ai_api_key` 写入百炼 key（`AI_API_KEY_FILE` 注入），微信凭证写 `secrets/wechat_*`。

Prometheus 抓取（P7-2c）——令牌只对指标端点生效，别复用 JWT 或业务 API Token：

```yaml
scrape_configs:
  - job_name: agent-flow
    metrics_path: /actuator/prometheus
    authorization:
      credentials_file: /etc/prometheus/agentflow_metrics_token   # 600，内容 = secrets/metrics_token
    static_configs:
      - targets: ["agent-flow-server:8080"]
```

未带令牌访问 `/actuator/prometheus` 返回 401；带正确令牌返回 200 且只授予 `role=metrics` 主体。`curl -H "Authorization: Bearer $(cat secrets/metrics_token)" localhost:8080/actuator/prometheus`。

镜像构建在容器内跑 Maven，`server/agent-flow-server/deploy/maven-settings.xml`（bind 挂载，不进镜像层）指阿里云公共仓 + BuildKit 依赖缓存挂载：首次完整构建实测约 2 分钟，之后改代码的增量重建都在 1 分钟内。

验证与冒烟：

```bash
curl -s localhost:8080/actuator/health/readiness          # {"status":"UP"}
curl -s localhost:8080/api/ping                            # prod 下 security.enabled=true → A0401 才对
docker compose logs agent-flow-server | grep -E "启动自检|Flyway|Started"
# 管理员由 bootstrap 配置注入（P7-2a：注册接口永不授予 admin）——先写口令再重启起服：
#   echo -n '<8-64位口令>' > secrets/bootstrap_admin_password
#   AGENTFLOW_BOOTSTRAP_ADMIN_USERNAME=<你> docker compose up -d --force-recreate
# 之后注册出来的都是普通用户，用 bootstrap 账号登录即可建凭证/跑创作：
curl -s -XPOST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
     -d '{"username":"<普通用户>","password":"<8-64位>"}'
# prod 下 security.enabled=true，创作脚本必须带身份：三选一（AF_TOKEN / AF_USER+AF_PASS / AGENTFLOW_API_TOKEN）
AF_USER=<你> AF_PASS=<8-64位> scripts/run-creation.sh --mode xhs --topic "秋天适合把书一本本读完" --ratio 3:4
```

安全边界（实测口径）：容器 `read_only: true` + `cap_drop: [ALL]` + `no-new-privileges` + tmpfs `/tmp`，运行期只在 `/app/data/library` 卷写盘；秘密经镜像内 `deploy/docker-entrypoint.sh` 白名单把 `*_FILE` 解析成同名 env 后 `unset` 掉 `*_FILE`，因此密钥不进 compose 明文、不进命令行、不进 `docker inspect`，日志只打印变量名。停机口径：`docker compose stop agent-flow-server` → `Graceful shutdown complete` 实测 1s 内完成（在途请求排空、Hikari 干净关闭）。

**主密钥轮换（P7-3#16，双读单写）**：

```bash
cd /path/to/SpringCloudAdmin/docker-compose/agent-flow
openssl rand -base64 32 > secrets/master_key.new     # 1) 生成新主密钥
cp secrets/master_key secrets/master_key_previous    # 2) 旧密钥进"上一把"，新密钥上位
mv secrets/master_key.new secrets/master_key
chmod 600 secrets/master_key secrets/master_key_previous
docker compose up -d --force-recreate agent-flow-server
# 3) 启动日志核对：master key kid 已变化，AGENTFLOW_MASTER_KEY_PREVIOUS present: true
# 4) 旧凭证在被工作流首次解密时自动重封装；用 kid 查还剩多少未迁移（把 <newKid> 换成日志里的值）：
docker compose exec agent-flow-db psql -U agentflow -d agentflow -tAc \
  "SELECT count(*) FROM user_credentials WHERE secret_json_enc NOT LIKE 'v2:<newKid>:%'"
# 5) 计数为 0 后清空旧密钥并重启（此后旧密钥彻底失效）：
: > secrets/master_key_previous && docker compose up -d --force-recreate agent-flow-server
```

旧格式（P3 起裸 Base64）与 `v2:{旧kid}` 密文在窗口内都可读；`previous` 只在解密时参与，新写入永远用新主密钥。**未迁移的凭证若提前清空 previous 将不可解**，所以第 4 步的计数必须归零。多实例部署时每个实例必须注入同一对主密钥/旧密钥（当前仍靠部署层保证）。

升级 / 回滚：先 `git -C /path/to/agent-flow checkout <good-sha>` 并按上面第 1 步重建镜像，再回 compose 目录 `docker compose up -d`（镜像 tag 不变时加 `--force-recreate`）；schema 只由 Flyway 向前迁移，回滚旧镜像前需确认迁移是向后兼容的（破坏性变更要配套备份 `docker compose exec agent-flow-db pg_dump -U agentflow agentflow > backup.sql`）。

备份与恢复演练（评审 P6 #9）：用 `scripts/backup-postgres.sh`，不要手敲 `pg_dump`——口令不落到任何文件，脚本只借 compose 进容器执行。

```bash
bash scripts/backup-postgres.sh backup                 # pg_dump -Fc + 产物库归档 → <compose>/backups/agentflow-<时间戳>.{dump,library.tgz}（0600，各保留最近 7 份）
bash scripts/backup-postgres.sh verify <dump>          # 只验结构可读，不代表能恢复
bash scripts/backup-postgres.sh drill                  # 恢复进临时库 agentflow_drill_*，逐表计数比对后自动 DROP
bash scripts/backup-postgres.sh run-daily [--drill]    # 定时入口：backup → verify（--drill 追加恢复演练），失败必落告警
bash scripts/backup-postgres.sh schedule 03:30         # macOS 登记 launchd 每日任务（Linux 会打印等价 crontab 行）
bash scripts/backup-postgres.sh unschedule             # 撤销登记
```

可覆盖：`--compose DIR`（compose 目录）、`--out DIR`（dump 目录）、`--keep N`、`--file <dump>`，以及环境变量 `SOURCE_DB`（默认 `agentflow`；演练时可指向 scratch 库）、`LIBRARY_DIR`（产物库目录，默认 `<compose>/data/library`——artifact 指向的真实文件在磁盘上，只备 PG 会丢产物）。`drill` 建/删临时库，任何失败路径（含 `pg_restore` 报错）都会清掉临时库，不在实例里留垃圾。

异地副本与告警（都是环境变量，不给就不假装做过）：
- `BACKUP_OFFSITE_DIR=<目录>`：dump 与归档 `cp` 过去并 `cmp` 逐字节核对，适合挂载盘/NFS/第二台机器；
- `BACKUP_OFFSITE_CMD=<命令>`：把两个文件路径追加给自定义上传器（`rclone copy` / `aws s3 cp` / `gsutil cp`）。**对象存储凭证只走云 CLI 自身配置或环境变量，绝不写进本仓库**；
- `BACKUP_ALERT_WEBHOOK=<url>` + `BACKUP_ALERT_LOG`（默认 `<out>/backup-alerts.log`）：任一阶段失败（含 `pg_dump` 自身失败、异地副本字节不一致、上传命令非 0）落一条 `FATAL` 行并 POST 一条 JSON，`launchctl`/`cron` 里没人看 stdout，靠这个留痕。

口径提醒：**没做过恢复演练的备份等于没有备份**；登记了定时不等于备份成功——每次接手先看 `backups/logs/backup.out` 与 `backup-alerts.log`。

已知排查点：本机 `docker` CLI 在 `~/.docker/bin`（不在默认 PATH）；宿主 18080 已被 RocketMQ dashboard 占用，临时探活换端口；Docker Desktop 的 socket 偶发 `permission denied`，重试即可（非应用问题）。nacos 启动噪声已在 P7-3#12 消除（见第 4 节）。
