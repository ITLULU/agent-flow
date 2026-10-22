# 工具与环境清单

> 原则：本清单只列**需要你自己安装/准备**的东西，代理不代下载。✅=本机已验证存在，📥=需你安装，☁️=云端/服务侧准备（无需本机下载）。

## 本机开发（台式机）

| 工具 | 版本要求 | 状态 | 用途 |
|---|---|---|---|
| Git | ≥ 2.40 | ✅ 2.49.0 | 版本管理 |
| JDK | 21.x | ✅ 21.0.7 | 后端编译运行（虚拟线程依赖 21） |
| Maven | ≥ 3.9 | ✅ 3.9.10 | 构建；本机 `~/.m2/settings.xml` 未配置镜像，当前直连 Central 可用（Boot 3.5.6 已缓存）。若后续下载慢，自行加阿里云镜像 `<mirror>` |
| Node.js | ≥ 20 LTS | ✅ v24.11.0 | Vue 前端构建（P4 起） |
| Docker Desktop | 最新稳定（WSL2 后端） | 📥 未安装 | 本地跑 PostgreSQL+pgvector / Redis、验证 compose（P1 起需要） |
| pnpm | ≥ 9（可选） | 📥 可选 | 前端包管理；不用则退回 npm |

## 云侧 / 服务侧（生产）

| 项 | 规格建议 | 状态 | 说明 |
|---|---|---|---|
| 云服务器 | 2C4G 起步（100 QPS 峰值够用），4C8G 稳妥，系统 Ubuntu 22.04/24.04，装 Docker + compose | ☁️ 待准备 | 本机+穿透只能做联调演示，不能承载 10w 用户 |
| PostgreSQL | 16 + pgvector 扩展（自托管容器或云数据库 RDS 均可） | ☁️ 待准备 | P1 定连接串口径；选型上优先带 pgvector 的（后续 RAG 场景免二次装） |
| Redis | 7.x | ☁️ 暂缓 | `-redis` profile 才需要，单体验证撑住 100 QPS 前不引入 |
| 域名 + HTTPS 证书 | 任意常规域名 | ☁️ 上线前 | 公众号/回调、平台对外接口都需要 |
| 内网穿透（仅联调期） | cloudflared（免费版即可）或自建 frp | ☁️/📥 二选一 | 云上不选，本机若需被外部回调联调再装 client |
| Git 远端 | GitHub（Actions 免费跑 CI）或 Gitee | ☁️ 待准备 | `.github/workflows/ci.yml` 已入库，push 即生效 |

## 密钥与账号（只给 env 变量名，值自行申请，不写进任何提交）

| 环境变量 | 来源 | 用于 |
|---|---|---|
| `AGENTFLOW_MASTER_KEY` | 本机 `openssl rand -hex 16` 生成 | 凭证库 AES-GCM 主密钥（P3） |
| `AGENTFLOW_API_TOKEN` | 自定强随机串 | 服务通道 token（`X-API-Token`）。P3 起业务身份改用 JWT；P7-1 起该主体 uid=0/role=ops，**不再是全站 admin**，只能碰 uid=0 名下资源 |
| `BAILIAN_API_KEY` | 阿里云百炼控制台（你已有） | LLM 节点：qwen 系列（OpenAI 兼容 + dashscope 原生） |
| `WANXIANG_*`（复用 BAILIAN_API_KEY） | 同上，需开通万相服务 | 生图节点异步任务 |
| `WECHAT_APPID` / `WECHAT_APPSECRET` | 公众号后台 | 草稿发布节点；注意 IP 白名单要加**云服务器出口 IP** |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | 你的 PG 实例 | prod profile 数据源（P1 起） |
