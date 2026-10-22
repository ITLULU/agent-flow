# agent-flow 企业级实施计划

> 定位：自研 Agent 自动化工作流平台（n8n 的自制替代），企业级标准开发，目标上线服务 10w 注册用户 / 3w 日活 / 峰值 100 QPS。
> 本文档是决策完备（decision-complete）的执行蓝图：按阶段顺序执行即可，不再需要选型决策。

## 1. 量级评估与架构结论

| 维度 | 数值 | 推论 |
|---|---|---|
| 注册用户 | 10w | 用户表/工作流表规模极小，单库足够 |
| 日活 | 3w | 平均触发 ≈ 3w 次/86400s < 1 QPS |
| 峰值 | 100 QPS | 瓶颈不在平台本身，在 LLM API 秒级响应 |
| 关键洞察 | 工作流执行必须**异步化** | 同步 API 只做 CRUD/查询（毫秒级），LLM/生图节点跑在后台执行器里，用户轮询/看执行历史 |

**架构结论：模块化单体**（一个 Spring Boot 应用、四个 Maven 模块、清晰边界保证将来可拆），不做微服务、不上 K8s、不用 MQ（执行队列用 DB 表 + 内存调度，档位内完全够用）。

**部署现状挂账**：当前用"本机 + 内网穿透"仅供内测；对 10w 用户正式上线**必须**迁移到云服务器（最低 2C4G，建议 4C8G），compose 文件第一天就写好，保证一天内可完成迁移。

## 2. 技术选型（版本钉死，禁止漂移）

### 2.1 后端（D:\source\agent-flow\server\agent-flow-server）

| 项 | 选择 | 说明 |
|---|---|---|
| JDK / 框架 | Java 21 + Spring Boot 3.5.x | 虚拟线程用于工作流执行器 |
| 模型接入 | **Spring AI 1.x 唯一抽象层 + Spring AI Alibaba** | ⚠️ 不引入 LangChain4j：与 Spring AI 是竞争性抽象（两套 ChatModel/Tool/RAG），混用=双倍维护+依赖冲突。"链/图/记忆"思想由自研引擎表达 |
| LLM Provider | 多模型可配置：`openai-compatible`（百炼 compatible-mode / DeepSeek / OpenAI 通用）+ `dashscope-native`（万相异步生图必须走原生协议） | 见 §5 LlmGateway |
| 数据库 | 默认 profile：H2 文件库（零外部依赖可启动）；`-prod` profile：PostgreSQL 16 + pgvector 扩展 | pgvector 一期只装不用（RAG 节点二期激活），挂账 |
| 缓存/限流 | 默认：Caffeine 进程内；`-redis` profile：Redis 7 全局限流 | 100 QPS 档位进程内限流足够，挂账 |
| schema 迁移 | Flyway（prod），H2 profile 用 `initSchema` 开关 | 禁止生产"启动即建表" |
| 连接池 | HikariCP，`maximum-pool-size=20`，配置文件注明换算：20 连接 × 1 实例 << PG max_connections=100 | |
| 爬虫/正文提取 | jsoup（显式超时+UA），正文可读化用 `net.dankito.readability4j` | 每个出网点走 §4.2 四件套基类 |
| 定时触发 | Spring `@Scheduled` + 自建 cron 注册表（DB 持久化，重启恢复） | 不引入 Quartz（档位过重） |

### 2.2 前端（D:\source\agent-flow\web\agent-flow-web）

| 项 | 选择 |
|---|---|
| 框架 | Vue 3.5 + Vite 6 + TypeScript + Pinia |
| 画布 | **LogicFlow 2.x**（滴滴开源，工作流/审批流场景专用，比 X6 开箱即用） |
| UI | Ant Design Vue 4.x |
| 请求 | axios + 统一拦截器（traceId 透传、错误结构体渲染） |

## 3. 模块划分（父 pom 只管版本，子模块自选依赖）

```
agent-flow-server/                     ← 父 pom（BOM import：spring-boot + spring-ai + spring-ai-alibaba）
├── platform-common/                   ← 无 Spring 依赖：领域模型、错误码、traceId 工具、外部调用四件套基类
├── workflow-engine/                   ← 核心资产：DSL 解析、DAG 校验、执行器、节点 SPI、重试/超时/降级契约。不依赖 Spring AI
├── node-adapters/                     ← 节点实现：LLM/生图/采集/公众号/条件/人工审核。依赖 Spring AI（唯一允许处）
└── server-application/                ← 启动类、Web 层、鉴权过滤器、全局异常、actuator、Flyway、触发器调度
```

依赖方向：`server-application → node-adapters → workflow-engine → platform-common`（单向，禁止反向）。

## 4. 工作流引擎设计（一期核心壁垒）

### 4.1 DSL（JSON 定义，画布与引擎共用同一份）

```json
{
  "name": "每日内容工厂",
  "trigger": { "type": "cron", "expression": "0 8 * * *", "timezone": "Asia/Shanghai" },
  "nodes": [
    { "id": "n1", "type": "http.collect", "params": { "url": "...", "timeoutMs": 20000 } },
    { "id": "n2", "type": "llm.chat", "params": { "provider": "bailian", "model": "qwen-plus", "promptRef": "...", "responseFormat": "json" } },
    { "id": "n3", "type": "image.wanxiang", "params": { "model": "wan2.2-t2i-flash", "size": "1024*1024", "pollTimeoutSec": 300 } },
    { "id": "n4", "type": "wechat.draft", "params": { "credentialRef": "wx-main" } }
  ],
  "edges": [ { "from": "n1", "to": "n2" }, { "from": "n2", "to": "n3" }, { "from": "n3", "to": "n4" } ]
}
```

### 4.2 节点 SPI（所有节点的强制契约）

```java
public interface NodeExecutor {
    String type();                                    // "llm.chat" 等，注册进 NodeRegistry
    NodeOutput execute(NodeInput in, ExecutionContext ctx);
    default RetryPolicy retryPolicy() { ... }         // 默认：2 次指数退避
    default Duration timeout() { return Duration.ofSeconds(60); }
    default Optional<NodeOutput> degrade(NodeInput in, Throwable t) { ... }  // 必须真实覆盖：拒绝/超时/协议异常三类
}
```

**四件套纪律（写进基类，不许逐处手搓）**：超时 + 捕获 + 降级 + 重试。降级返回必须带 `degraded=true` 标志，禁止静默空集合。每类降配有 mock 故障单测钉住。

### 4.3 执行模型

- 拓扑排序 + 环检测在**保存时**做（非法 DAG 直接 400），执行时按拓扑序逐节点跑；无分支场景一期串行即可（并行分支二期）
- 每次执行一条 `execution` 记录 + 每节点一条 `execution_node_log`（输入输出摘要、延迟、degraded 标志、traceId）
- 执行器线程：Java 21 虚拟线程，`Executors.newVirtualThreadPerTaskExecutor()` + 信号量限并发（默认 16，防打爆 LLM API 配额——注释里写清：这是全局唯一的"执行饱和度"口径）
- 失败恢复：执行幂等可重跑（`POST /executions/{id}/retry`），公众号草稿节点幂等由"先查当日同名草稿"实现

## 5. LlmGateway（多模型可配置的实现）

```java
public interface LlmGateway {
    ChatResult chat(ChatRequest req);                    // openai-compatible 协议族（百炼/DeepSeek/OpenAI）
    ImageTask submitImage(ImageRequest req);             // dashscope 原生异步
    ImageTask queryImage(String taskId);
}
```
- `provider` 字段路由到适配器；api-key 从 `credential` 表读取（AES-GCM 加密存储，主密钥只从环境变量 `AGENTFLOW_MASTER_KEY` 注入，配置文件零真实密钥）
- 全部 HTTP 走显式超时的 `RestClient`（禁裸 create），Spring AI 负责 ChatClient/Tool 抽象，万相异步协议自己实现（Spring AI 不覆盖该协议）

## 6. 企业底座十节落地映射（按 skill 档位：全部必做，深度按 ≤1k QPS 裁剪）

| skill 节 | 落地物 | 验收 |
|---|---|---|
| 1 仓库 | `.gitignore`（含 `.env`/`*secret*`/`*.pem`/H2 数据目录）、README 四节、父 pom 只管版本 | 干净目录按 README 跑通 |
| 2 配置 | 默认 profile=H2+内存缓存零依赖启动；`-prod`/`-redis` 独立 profile；每开关 yml=代码=文档三处一致 | 不启动任何中间件 `mvn spring-boot:run` 成功 |
| 3 外部调用 | `NodeExecutor` 基类四件套 + grep 无裸 builder | 三异常单测 CI 绿 |
| 4 异常边界 | `@RestControllerAdvice`：`{code,message,traceId}`；入参 null/超长/路径穿越在过滤器兜底 | 故意 400/500 返回结构体 |
| 5 安全 | JWT 鉴权过滤器骨架（`security.enabled` 开关默认 false 于 local）；credential 加密；SENSITIVE 端点清单（草稿发布/凭证写/执行触发）打注解标 | `security.enabled=true` 无 token 401 |
| 6 可观测 | actuator liveness/readiness 分离 + MDC traceId 贯穿并回传响应头 + 四金指标口径文档（QPS/RT P50/P99/错误率/执行器饱和度） | 一次请求日志与响应头 traceId 相同 |
| 7 存储 | Flyway V1 脚本、HikariCP 换算注释、批量写 batch+事务、`execution_node_log` 查询禁 SELECT * 收敛 DAO | 注入第 N 条失败断言无半套数据 |
| 8 CI 测试 | GitHub Actions：构建+全模块测试+yml 默认值冒烟断言；每模块 test 目录非空（测试对称）；DSL/REST 契约测试 | Actions 首跑绿 |
| 9 部署 | `docker-compose.yml`（server+web+postgres+redis，redis 注释可选）、`server.shutdown=graceful`、启动自检 Runner 打印 profile/依赖连通/开关值 | compose config 校验过 |
| 10 挂账 | §8 已知局限清单 + 落地当天跑 `enterprise-project-review` 首飞 | 评审无 🚨 |

## 7. 分阶段实施步骤（每阶段有 Definition of Done，按序执行）

> **进度以 `docs/PROGRESS.md` 台账为准**（每子任务完结时间+证据回写），本节只维护计划口径。现状：P0 ✅（2026-10-19 11:25）、P1 ✅（2026-10-19 13:47）、P2 ✅（2026-10-19 15:03）、P3 ✅（2026-10-19 17:45）、P4A 创作流水线 ✅（2026-10-22 00:42，用户目标插队，详见台账与 `docs/CREATION-GUIDE.md`）、P4 ⬜。注：P3 因禁下载约束以纯 JDK 实现替代 Caffeine/JWT 库（固定窗限流 + 自实现 HS256），详见台账设计决策。

### P0 仓库底座（0.5 天）
1. `git init` + `.gitignore` + README 四节骨架
2. 父 pom + 四模块空壳，`mvn compile` 通过
3. server-application：启动类 + 默认 profile（H2）+ actuator + 全局异常 + traceId 过滤器 + 启动自检 Runner
- **DoD**：零中间件启动成功，`/actuator/health` 200，故意 404 返回结构体

### P1 引擎核心（3 天）
1. DSL 模型类 + JSON 序列化（platform-common）
2. DAG 校验（环/孤立节点/未知 type）+ 拓扑排序（workflow-engine）
3. 执行器（虚拟线程 + 信号量限并发）+ 节点生命周期（重试/超时/degraded 记录）
4. execution / execution_node_log 表 + Flyway + DAO
- **DoD**：单元测试覆盖合法/非法 DSL、节点失败重试、降级标志；用硬编码 echo 节点跑通一条 3 节点工作流

### P2 节点适配器（3 天）
1. LlmGateway + openai-compatible 适配器（百炼/DeepSeek 同协议）+ dashscope 万相异步适配器（提交/轮询/超时）
2. http.collect（jsoup 正文提取）、llm.chat、image.wanxiang、wechat.draft（token→上传素材→draft/add，errcode 全部映射中文错误码）、condition.if、human.approve（等待回调）
3. credential 表 + AES-GCM 加密 + 管理 API
- **DoD**：REST 触发"采集→LLM 选题→生图→草稿"四节点工作流，公众号未配置时降级为"产出 HTML+图片URL 存执行日志"且 degraded=true；每个适配器有三异常降级单测

### P3 平台 API（2 天）
1. 工作流 CRUD + 版本保存（DSL 快照）+ cron 触发注册（重启从 DB 恢复）
2. 执行查询/手动触发/重跑；用户注册登录 + JWT；限流（Caffeine token-bucket：单用户 10 req/s，全局 200 req/s 兜底）
- **DoD**：Swagger(knife4j) 全接口可调用；鉴权开关打开后全端点受控；SENSITIVE 清单落文档

### P4 前端画布（4 天）
1. Vite 脚手架 + 登录 + 工作流列表
2. LogicFlow 画布：节点面板（六类节点拖拽）+ 属性表单（schema 驱动）+ 保存即校验回显
3. 执行历史视图：节点状态时间线 + degraded 标黄 + traceId 跳转后端日志
- **DoD**：在画布上拖出内容流水线四节点并成功执行一次，看到草稿产物

### P5 部署与首飞（1 天）
1. docker-compose + 前端 nginx 容器 + graceful shutdown + 环境变量注入文档
2. 内网穿透内测指南（推荐 Cloudflare Tunnel，免公网 IP；frp 备选）；正式上线迁云服务器 checklist
3. CI 补齐；跑一轮 `enterprise-project-review` 首飞，🚨 项当天回修
- **DoD**：`docker compose up` 一键起全栈；README 快速开始逐条真实执行过

### 合计 ≈ 14 个工作日。节奏建议：P0-P1 一口气做完再碰 P2（引擎不稳做节点是返工）。

## 8. 已知局限挂账（延后项，非跳过项）

| 项 | 触发条件 | 档位现状 |
|---|---|---|
| pgvector RAG 节点（本地参考文档） | 二期，引擎就绪后加 `rag.retrieve` 节点 | pgvector 已随 compose 安装，代码未接 |
| Redis 全局限流/分布式执行锁 | 上云且多实例部署时 | 进程内 Caffeine 代替 |
| 并行分支/子工作流 | 出现真实多分支流水线需求 | 引擎已按 DAG 设计，只缺并行调度 |
| 多租户/节点市场/插件热加载 | 用户量 >50w 或开放生态 | 表已预留 tenant_id 默认值 |
| 告警通知（执行失败推送） | 内测反馈收集期后 | 执行日志已可查 |

## 9. 容量核对（100 QPS 峰值下的单机推演）

- 同步 API（CRUD/查询）100 QPS × 平均 5ms = 0.5 核占用 → 无压力
- 异步执行：假设日活 3w 用户人均 1 条工作流、每条 4 节点、LLM 节点 3s → 峰值时段并发执行 ≈ 3w×4 节点/(3h) ≈ 11 节点/s，信号量 16 即可扛，无需扩容
- DB：峰值写入 ≈ 50 行/s（执行日志），HikariCP 20 连接余量充足
- **第一断裂点预测**：LLM API 配额（百炼默认 RPM/TPM），不是应用本身——上线前需在百炼控制台申请配额上调，压测时优先验证此点
