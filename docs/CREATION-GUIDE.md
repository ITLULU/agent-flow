# 创作流水线使用指南（小红书 / 微信公众号）

> 适用版本：P4A 创作流水线（节点：`hotspot.fetch` / `article.extract` / `copy.write` / `image.cover` / `publish.local` / `xhs.draft` / `wechat.draft`）。
> 闭环判据：`P4CreationPipelineE2ETests`（两条通道各一条端到端用例，桩替代外部服务，`mvn verify` 每次全量执行）。

## 1. 三种输入 × 两条输出通道

| 输入方式 | 首节点 | 必填参数 | 说明 |
|---|---|---|---|
| 抓热点 | `hotspot.fetch` | `sources`（默认 `weibo,baidu,toutiao,zhihu`）、`limit` | 多源合并去重；单源失败只记 `failedSources`，全失败才报错；热榜整体不可用时**降级**为空条目，下游改用用户自带话题继续 |
| 给一篇公众号文章/任意链接 | `article.extract` | `url`（可选 `selector`） | 抽取正文 `text` 当素材、标题当主题；正文自动截断入素材区，仅做观点参考、要求原创重写 |
| 给想法/话题 | 无 | `copy.write.topic` | 不接上游取数，直接以此主题创作 |

| 输出通道 | 文案节点 mode | 发布节点 | 落地形态 |
|---|---|---|---|
| 小红书图文 | `xiaohongshu` | `xhs.draft` | 成品库 `kind=note`：`note.txt` + `xhs-package.json` + 图片；若配 `endpoint` 则 POST 图文包（无人值守发布口子） |
| 微信公众号 | `wechat` | `wechat.draft` | 成品库 `kind=article`：`article.md` + `meta.json` + `assets/*.png`；同时推微信草稿箱（正文按段落转 `<p>` HTML，封面走素材库上传拿 `thumb_media_id`） |

`copy.write` 的 mode 简写 `xhs` 不接受，必须写全名 `xiaohongshu`。

## 2. 主图比例：预设名或任意 a:b

`image.cover` 的 `ratio` 参数（也可直接给 `size: "1024*1024"` 走同套范围校验）：

| ratio | 尺寸 | 用途 |
|---|---|---|
| `square` / `1:1` | 1024\*1024 | 通用方图、微信小图 |
| `xhs` / `xiaohongshu` / `portrait` / `3:4` | 768\*1024 | 小红书封面（推荐） |
| `landscape` / `4:3` | 1024\*768 | 横版配图 |
| `story` / `9:16` | 720\*1280 | 竖屏 |
| `banner` / `16:9` | 1280\*720 | 横幅 |
| `wechat-share` / `2.35:1` | 1200\*512 | 公众号分享大图（推荐） |

任意比例按长边 1024 折算，边长强制落在 512–1440；用户写 `3:4` 成品就记 `3:4`（不会被回写成 `0.75:1`），写 `8:12` 归一成 `2:3`。文件名带比例，如 `cover-1-3x4.png`。

## 3. 一条命令跑完整链路

```bash
cd /Users/a/source/agent-flow
export BASE_URL=http://localhost:8080
# 本地默认 agentflow.security.enabled=false：可不带凭证；开了鉴权则用 AF_TOKEN 或 AF_USER/AF_PASS
scripts/run-creation.sh --mode xhs --topic "秋天适合把书一本本读完" --ratio 3:4
scripts/run-creation.sh --mode wechat --url https://mp.weixin.qq.com/s/xxxx --ratio 2.35:1
scripts/run-creation.sh --mode xhs --source hot --illustrations 2 --publish local
scripts/run-creation.sh --mode wechat --topic "..." --credential-id 12 --dry-run   # 只看将要提交的 DSL
```

脚本做四件事：拼 DSL 并打印 → `POST /api/workflows` → `POST /api/workflows/{id}/executions` → 轮询 `/api/executions/{id}` **顶层 status** 到终态，再把最新成品的所有文件下载到 `--out` 目录。

## 4. 手工 API 序列（等价拆解）

DSL 骨架（`trigger` 是**对象**不是字符串，写成 `"manual"` 会 A0400 解析失败）：

```json
{
  "name": "xhs-autumn-reading",
  "trigger": { "type": "manual" },
  "nodes": [
    { "id": "a", "type": "hotspot.fetch", "params": { "limit": 20 } },
    { "id": "b", "type": "copy.write",    "params": { "mode": "xiaohongshu", "topic": "秋天适合把书一本本读完", "length": 700, "tone": "温柔克制" } },
    { "id": "c", "type": "image.cover",   "params": { "kind": "cover", "ratio": "3:4", "n": 1 } },
    { "id": "e", "type": "publish.local", "params": {} },
    { "id": "f", "type": "xhs.draft",     "params": {} }
  ],
  "edges": [
    { "from": "a", "to": "b" }, { "from": "b", "to": "c" },
    { "from": "c", "to": "e" }, { "from": "e", "to": "f" }
  ]
}
```

节点 id 用字典序命名（a/b/c/…）以保证同层拓扑顺序确定；`params` 不做变量插值，下游节点自己读上游输出（标题/正文/封面图 URL 全部有上游回退）。

```bash
# ① 登录拿 JWT（enabled=true 时）
curl -s -X POST $BASE_URL/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"me","password":"***"}' | jq -r .data.token

# ② 存工作流（保存即校验：未知节点类型/环/悬空边直接 C0500 拒绝）
curl -s -X POST $BASE_URL/api/workflows -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' --data @dsl.json | jq .data   # → workflowId

# ③ 触发（异步，秒回 executionId）
curl -s -X POST $BASE_URL/api/workflows/1/executions -H "Authorization: Bearer $TOKEN" | jq .data

# ④ 看执行明细：节点级状态/尝试次数/耗时/降级原因
curl -s $BASE_URL/api/executions/7 -H "Authorization: Bearer $TOKEN" \
  | jq '.data.status, .data.nodes[] | {nodeId,nodeType,status,attempts,durationMs,reason}'

# ⑤ 成品库：列表 → 明细 → 文件清单 → 下载单个文件
curl -s "$BASE_URL/api/artifacts?kind=article&limit=5" -H "Authorization: Bearer $TOKEN" | jq '.data[] | {id,title,kind}'
curl -s  "$BASE_URL/api/artifacts/3/files" -H "Authorization: Bearer $TOKEN" | jq .data
curl -s -o article.md "$BASE_URL/api/artifacts/3/file?path=article.md" -H "Authorization: Bearer $TOKEN"
```

轮询判据必须用顶层 `.data.status`（终态集合：`SUCCESS` / `SUCCESS_WITH_DEGRADED` / `FAILED`）。节点日志是增量落库的，明细里出现节点级 `"SUCCESS"` 不代表整条执行结束——这是脚本 `await terminal` 的由来。

## 5. 装配与凭证口径

| 需要的配置 | 键 | 缺失时行为 |
|---|---|---|
| 文案模型 | `agentflow.nodes.llm.api-key` ← `AI_API_KEY`（`BAILIAN_API_KEY` 兼容） | 节点在位，执行报 A0400「LLM 网关未配置」，不阻塞启动 |
| 生图模型 | `agentflow.nodes.image.api-key` ← `AI_API_KEY`（万相同账号） | 无 key 时 `image.cover` 报不可重试配置错；生图失败本身**降级**为无图继续发布 |
| 成品库目录 | `agentflow.content.library-dir`（默认 `./data/library`） | 启动自检做写探针，不可写直接打错误日志预告 `publish.local`/`xhs.draft` 会失败 |
| 微信凭证 | 环境变量 `WECHAT_APPID/WECHAT_SECRET`，或 `wechat.draft.credentialId` 指向凭证库 | 库内 AES-GCM 加密存储，API 永不回显明文；未配主密钥时凭证接口报 A0400，其它功能不受影响。**`credentialId` 只认"这条工作流的归属者"**（P7-1）：别人的凭证 id 填进自己的 DSL 会报"凭证不存在或无法解密"且不会出网，这是刻意的——不区分"不存在"与"无权"，免得拿它探测他人凭证 |

生产部署务必：`agentflow.security.enabled=true` + `AGENTFLOW_JWT_SECRET`（≥32B）+ `AGENTFLOW_MASTER_KEY`（base64 32B）。微信通道还需把服务器出网 IP 加进公众号 IP 白名单。

## 6. 已知边界

- `xhs.draft` 无官方公开草稿 API：默认只产出**小红书图文包**（标题/正文/话题/图片清单）落本地成品库；配 `endpoint` 才外发，失败记 warnings 不判 FAILED。
- 热榜源结构变更最易表现为「取到 JSON 但零条目」，节点会显式记 `failedSources`；`endpointOverride` 可换源不改码。
- 引擎已支持同层并发（P7-3#18：按依赖分层、同层节点同时跑，宽度 `agentflow.engine.max-parallel-nodes` 默认 8）。本文推荐流水线是 a→b→c→e→f 单链，所以配图与主图仍逐个产出；想让两者并发就把它们挂成同一上游的两个兄弟节点（下游各自读上游输出，无需改代码）。
- 真 key 联调：注入 `AI_API_KEY`（+ 微信 appid/secret）后跑上面第 3 节命令即可；文案 + 生图 + 本地发布三段已用真实 key 实测 SUCCESS（微信草稿仍需真 appid/secret）。
