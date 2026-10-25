# 创作流水线使用指南（小红书 / 微信公众号）

> 适用版本：P4A 创作流水线 + P4B 交付形态强化（节点：`hotspot.fetch` / `article.extract` / `copy.write` / `copy.originality` / `image.cover` / `publish.local` / `xhs.draft` / `wechat.draft`）。
> 闭环判据：`P4CreationPipelineE2ETests`（两条通道各一条端到端用例，桩替代外部服务，`mvn verify` 每次全量执行）；排版与配图的细则在 `WechatHtmlLayoutTest` / `WechatDraftNodeExecutorTest`，原创分校验在 `OriginalityCheckNodeExecutorTest`，文件名口径在 `LocalArchiveNodeExecutorTest` + `ArtifactIntegrityE2ETests`。

## 1. 三种输入 × 两条输出通道

| 输入方式 | 首节点 | 必填参数 | 说明 |
|---|---|---|---|
| 抓热点 | `hotspot.fetch` | `sources`（默认 `weibo,baidu,toutiao,zhihu`）、`limit` | 多源合并去重；单源失败只记 `failedSources`，全失败才报错。**只要有任何一个源失败，本节点就自报 `degraded=true`**（节点行显示 DEGRADED、整次执行升到 `SUCCESS_WITH_DEGRADED`），四源只剩一源不会看着像"全绿"；热榜整体不可用时**降级**为空条目，下游改用用户自带话题继续 |
| 给一篇公众号文章/任意链接 | `article.extract` | `url`（可选 `selector`） | 抽取正文 `text` 当素材、标题当主题；长素材进 `copy.write` 先蒸馏成要点再创作（不整段照抄），跑完用 `copy.originality` 量化与原文的相似度 |
| 给想法/话题 | 无 | `copy.write.topic` | 不接上游取数，直接以此主题创作 |

| 输出通道 | 文案节点 mode | 发布节点 | 落地形态 |
|---|---|---|---|
| 小红书图文 | `xiaohongshu` | `xhs.draft` | 成品库 `kind=note`：`{标题}-{yyyyMMddHHmmss}.md` + `xhs-package.json` + 图片；若配 `endpoint` 则 POST 图文包（无人值守发布口子） |
| 微信公众号 | `wechat` | `wechat.draft` | 成品库 `kind=article`：`{标题}-{yyyyMMddHHmmss}.md` + `meta.json` + `assets/*.png`；同时推微信草稿箱（纯文本正文经内联样式排版器渲染，见 §2.1；封面走 `material/add_material` 拿 `thumb_media_id`，正文配图走 `media/uploadimg` 换永久直链） |

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

`kind` 决定这张图下游怎么用：`cover` 给草稿箱当封面（`thumb_url`），`illustration` 才会被 `wechat.draft` 取进正文当配图——主图不进正文，否则开头会出现和第二张封面一模一样的图。`n` 出 1–4 张。

### 2.0.1 场景一键出图：scene / 画风 / 参考配方 / 四宫格

不想记比例就填 `scene`，它同时决定比例和提示词骨架（`prompts/image/*.txt`）：

| scene | 比例 → 尺寸 | 提示词骨架 | 典型用途 |
|---|---|---|---|
| `xhs-cover` | 3:4 → 768\*1024 | 手机实拍感、窗光、细颗粒 | 小红书信息流主图 |
| `xhs-body` | 1:1 → 1024\*1024 | 平铺俯拍、哑光材质 | 小红书正文配图 |
| `wechat-cover` | 2.35:1 → 1200\*512 | 编辑部级横幅、留白压字 | 公众号头图/分享图 |
| `wechat-chapter` | 4:3 → 1024\*768 | 概念化极简、可叠标题 | 公众号章节小图 |

`scene` 与 `ratio`/`size` 同时给时，**显式 `size` > `scene` > `ratio`**；`kind` 由 scene 决定（`xhs-body`/`wechat-chapter` 记 `illustration`，其余记 `cover`），仍可用 `kind` 覆盖。

另外四个参数是"不像 AI"的主要抓手：

- `promptStyle`：预设画风 `film|street|minimal|warm|dark|vintage`，展开成英文风格词（如 film → `analog film photo, portra 400 color, light leak, soft grain…`）。
- `style`：自由文本风格描述（中英文都行），与 `promptStyle` **叠加生效**，不是二选一。
- `referenceStyle`：参考图的风格配方（英文提示词片段）。生图工作台会先用 qwen-vl 把参考图拆成 8 个维度再压成这一段；画布上也可以直接手填。
- `negativePrompt`：默认已带一串反 AI 味负向词（`plastic skin, waxy face, AI smoothness, perfect symmetry, studio lighting, uncanny valley, text watermark, extra fingers…`），要换整套就整段覆盖。

`collage: "2x2"` = 一次出 4 张并拼成四宫格（格间 3px 白缝，等比居中裁切不拉伸），**拼图和 4 张单图都会交付**：输出里 `images[]` 是单张（各自 `localPath`/`bytes`），`collage` 是拼图（`localPath` 形如 `.staging/{executionId}/collage-2x2-1x1.png`）。拼接失败不阻断单张，但会显性写 `collageError`（例如万相只回了 2 张：`四宫格需要 4 张成图，实际只拿到 2 张`）——不允许静默少一张图。

提示词合成顺序固定为 **主体（`prompt`/`topic`/上游标题）→ `referenceStyle` → 场景骨架 → `style` → `promptStyle`**，回显在输出的 `prompt` 字段里，画布抽屉和工作台"实际喂给模型的提示词"都能看到最终那一句。不填 `scene` 时保持旧口径（中文骨架"为内容《X》设计封面主图…"），既有流程零影响。

### 2.0.2 生图工作台：不搭工作流也能出图

独立前端项目 `web/agent-image-studio`（`npm install && npm run dev` → `http://127.0.0.1:5174/`），只做一件事：参考图 → 风格配方 → 换成自己的内容 → 按场景出图。与画布应用互不依赖，但共用同一个后端与同一个 `image.cover` 执行器——页面里调好的配方搬到流水线上是同一套口径；反之亦然。

- **参考图**：选文件或 Cmd+V 粘贴（≤2MB）。点"解析这张图的风格"调 `POST /api/image/studio/analyze-reference`，qwen-vl 回 8 个维度（主体与机位/构图/配色/光线/质感/氛围/版式/去 AI 味细节），**每一项都可以在页面上改**，改完的英文配方才是真正喂给生图模型的东西（参考图本身不进模型——万相 t2i 没有风格条件入参，这是已知边界）。
- **出图**：`POST /api/image/studio/generate`，body `{scene, topic, style, referenceStyle, negativePrompt, model, n, collage, raw}`；`raw=true` 表示提示词整段自己写，不再叠场景骨架与配方。
- **取图**：`GET /api/image/studio/file?jobId=&name=`（需 Authorization，页面用 fetch+blob 取回后预览/下载）。jobId 是**负数**——独立生图借用内容库的 `.staging/{jobId}/` 目录，取负号保证永远不会和工作流执行（正数 executionId）的暂存区撞目录。
- 未配 `AI_API_KEY` 时：解析参考图报 A0400「参考图解析需要生图凭证」，出图报不可重试配置错——不会静默返回一张空图。

## 2.1 公众号排版：主题色 + 内联样式

`wechat.draft` 的 `theme` 参数（`moss` 默认 / `azure` / `amber` / `ink`），未知值直接 A0400 并列出可选项，不会静默用默认色。

- 微信编辑器会清洗 `class` 与 `<style>`，所以排版器产出的**每一个样式都是内联的**（这一点由 `WechatHtmlLayoutTest` 断言全篇无 `class=`）；正文若本来就是 HTML，原样透传、不二次包装。
- 识别 `#`–`####` 小标题、`>` 引用、`-`/`1.` 列表、`---` 分隔线、`**加粗**`、`![](url)` 图片，以及中文序号标题（`一、…`、`第三节 …`）；小标题自动带 `01` 编号徽章与渐变色条，连续普通行合并成一段（软换行不丢）。
- 配图按**小节**均匀分布插入锚点；小节不够就退化到段落锚点，图不会全挤在开头。

## 2.2 正文配图：必须是永久直链

草稿箱不代理外链，把万相返回的临时地址写进正文，2 小时后图就裂开。`wechat.draft` 的做法：取上游 `image.cover` 里 `kind=illustration` 的图（或用 `inlineImages` 参数显式给 URL 数组，走"高级 JSON"编辑），逐张 `media/uploadimg` 换成微信自己的 `mmbiz` 永久直链。

- 单张 >1MB 会被**跳过并写进输出的 `warnings`**（接口硬上限 jpg/png <1MB），一张都传不上去也照样记警告——图丢了是显性可见，不是悄悄少一张。
- 输出里 `inlineImages` 是最终进正文的 URL 清单，`contentChars` 是排版后正文长度，可用来核对"排版真的生效了"。

## 2.3 原创度校验：`copy.originality`

| 参数 | 默认 | 说明 |
|---|---|---|
| `source` | 取上游 `article.extract`/`http.collect` 的 `text` | 原始文章；取不到会照实在报文里写"原文缺失"，不会假装 0 分通过 |
| `generated` | 取上游创作产物的 `body` | 生成稿 |
| `maxScore` | 25 | 允许的相似度上限 |
| `strict` | false | `true` 时超阈值让节点失败（整条链路 FAILED），`false` 只标 `degraded` |
| `useLlm` | true | 叠加大模型评分（`model` 默认 `qwen-plus`）；`false` 表示只要本地口径，正常通过不降级 |

输出 `similarityScore`（0–100）、`riskLevel`、`maxCommonRun`（最长连续重合字数）、`localOverlap`、`method`（`llm+local` / `local-only`）与措辞/结构/主题/表达四维明细。口径：本地字符 5-gram 包含率 + 最长连续公共片段，与模型评分**取更严的一侧**；连续重合 ≥24 字直接抬到 60 分并降级——不给"整体分数看着低、其实整段照搬"留缝隙。

**模型这条腿缺失时不会给你绿色**：`useLlm=true` 却没拿到可解析的评分（网关未配、调用异常、报文不是 JSON、JSON 里没有 `similarityScore`）时，输出多一个 `llmNote` 写明是哪一种（异常时带报文头部 120 字），整条执行升到 `SUCCESS_WITH_DEGRADED`，`strict=true` 直接阻断发布段。本地 5-gram 只看得出整句照抄，看不出"换了词但沿用骨架"——实测同一篇参考文，执行 15 模型口径 68/high，执行 16 模型缺位时本地口径 0/low，那个 0 分是假绿。报文解析走两级：先按原样解析（模型在值里用全角引号摘录原文是常态），失败才把全角引号换成语法引号再试；反顺序会把合法 JSON 换碎并静默退回本地口径，这正是当初 `JsonParseException` 的来源（`curlyQuotesInsideStringValuesKeepParsing` 钉住）。

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

脚本做四件事：拼 DSL 并打印 → `POST /api/workflows` → `POST /api/workflows/{id}/executions` → 轮询 `/api/executions/{id}` **顶层 status** 到终态，再把本次成品的所有文件下载到 `--out` 目录（成品 id 直接取 `publish.local` 节点输出里的 `artifactId`，不是"库里最新一条"——并发跑多条流水线时后者会拿错稿）。

### 3.1 归档优先：外发失败不丢稿

DSL 的固定顺序是 `… → publish.local → wechat.draft|xhs.draft`：**先落库落盘，再谈外发**。所以草稿推送失败时（凭证错、IP 不在白名单、微信侧 5xx），

- 成品行已在 PostgreSQL `artifact` 表里，`{标题}-{yyyyMMddHHmmss}.md` / `meta.json` / `assets/cover-*.png` 已在 `library-dir` 下落盘，`/api/artifacts/incomplete` 里不会出现它（`markComplete` 已执行）；
- 执行终态仍是 `FAILED`（不粉饰：`wechat.draft` 声明了"不降级"，发半截比不发更糟），脚本打印失败节点原因后**额外打印 `成品 #<id>` 与取回命令**再 exit 1；
- 实测（2026-10-25 17:19，执行 8）：`copy.write/image.cover/publish.local` 三段 SUCCESS、`wechat.draft` 因 `errcode=40125 invalid appsecret` FAILED，成品 #4 的文件清单与正文文件下载均正常。（这条记录在"正文文件叫 `article.md`"的口径下采集，P4B 之后同名文件带标题与时间戳。）
- 要重发不必重跑创作：直接对成品 #id 起一条只有 `wechat.draft` 的流水线会缺上游正文，正确做法是修好凭证后整条重跑（创作段可加 `--publish none` 先只出成品）。

### 3.2 不写命令：在画布上跑同一条链路

节点面板按 采集 / 创作 / 发布 / 调试 分组，拖出 `正文抽取 → 文案成稿 → 封面主图 →（正文配图）→ 成品归档 → 原创度校验 → 公众号草稿` 并连线，就是 §3 那条命令的等价物。校验排在归档之后是刻意的：它是**发布闸门**，`--strict` 拦下时草稿不外发，但稿子已经落库落盘。画布上"运行"之后：

- 底部抽屉轮询执行明细，逐节点显示 状态 / 尝试次数 / 耗时，标题带 `#执行id` 与 traceId，终态为 `成功`/`部分成功`/`失败`；相似度超阈值时 `原创度校验` 显示 DEGRADED、整次执行升到"部分成功"（`strict=true` 才 FAILED）；
- "最近成品"条列出最近 5 条成品，点一下即取文件清单并按实际文件名下载正文（封面走 `assets/cover-*.png`）；
- 只想先出成品、不发草稿：把最后一个发布节点从画布上摘掉再运行（保存即校验，悬空边会被拒），这与 `--publish none` 同义。
- 实测（2026-10-25 17:45，`canvas-smoke-localonly` / workflow 15 / 执行 14 / 成品 #8）：四节点链路在浏览器里跑完，节点耗时 844ms + 14.1s + 6.9s + 15ms ≈ 22s，成品文件下载成功，全程未使用 curl。

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
    { "id": "d", "type": "publish.local", "params": {} },
    { "id": "e", "type": "xhs.draft",     "params": {} }
  ],
  "edges": [
    { "from": "a", "to": "b" }, { "from": "b", "to": "c" },
    { "from": "c", "to": "d" }, { "from": "d", "to": "e" }
  ]
}
```

外链改写走公众号时同骨架换节点：`a` 用 `article.extract{url}`，`b` 之后依次是图片与 `publish.local`，归档之后插 `{ "id": "f", "type": "copy.originality", "params": { "maxScore": 25 } }`（`source`/`generated` 都自动取上游），末节点 `wechat.draft` 可带 `{ "theme": "moss" }`；`run-creation.sh` 生成的就是这条链路（含一张 `{ "type": "image.cover", "params": { "kind": "illustration", "n": 2 } }`），可用 `--dry-run` 打印对照。

节点 id 用字典序命名（a/b/c/…）以保证同层拓扑顺序确定；`params` 不做变量插值，下游节点自己读上游输出（标题/正文/封面图 URL/配图/原文正文全部有上游回退）。

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
curl -s "$BASE_URL/api/artifacts?kind=article&limit=5" -H "Authorization: Bearer $TOKEN" | jq '.data.items[] | {id,title,kind,file_state}'
curl -s  "$BASE_URL/api/artifacts/3/files" -H "Authorization: Bearer $TOKEN" | jq .data
# 正文文件名是 {标题}-{yyyyMMddHHmmss}.md，不能写死——先取清单再下载那一项
MD=$(curl -s "$BASE_URL/api/artifacts/3/files" -H "Authorization: Bearer $TOKEN" | jq -r '.data[] | select(endswith(".md"))')
curl -s -o "$MD" "$BASE_URL/api/artifacts/3/file?path=$MD" -H "Authorization: Bearer $TOKEN"
# ⑥ 完整性核对与补偿（P7-3#12a）：半套成品看得见，文本能重建，缺图如实报
curl -s "$BASE_URL/api/artifacts/incomplete" -H "Authorization: Bearer $TOKEN" | jq '.data.items[] | {id,title,file_state,file_error}'
curl -s -X POST "$BASE_URL/api/artifacts/3/repair" -H "Authorization: Bearer $TOKEN" | jq '.data | {state,restored,missing}'
# ⑦ 远端副本（P7-3#12b，需 agentflow.content.storage.enabled=true）：待同步清单 + 手动推送
curl -s "$BASE_URL/api/artifacts/sync/pending" -H "Authorization: Bearer $TOKEN" | jq '.data | {enabled, items: [.items[] | {id,title,sync_state,sync_error}]}'
curl -s -X POST "$BASE_URL/api/artifacts/3/sync" -H "Authorization: Bearer $TOKEN" | jq '.data | {state,files,bytes,prefix}'
# 换实例/重建卷后本地目录是空的：只要 sync_state=synced，下面的下载会从远端取回同一份字节（先按 sha256 校验）
curl -s -o cover.png "$BASE_URL/api/artifacts/3/file?path=assets/cover-1-3x4.png" -H "Authorization: Bearer $TOKEN"
```

轮询判据必须用顶层 `.data.status`（终态集合：`SUCCESS` / `SUCCESS_WITH_DEGRADED` / `FAILED`）。节点日志是增量落库的，明细里出现节点级 `"SUCCESS"` 不代表整条执行结束——这是脚本 `await terminal` 的由来。

## 5. 装配与凭证口径

| 需要的配置 | 键 | 缺失时行为 |
|---|---|---|
| 文案模型 | `agentflow.nodes.llm.api-key` ← `AI_API_KEY`（`BAILIAN_API_KEY` 兼容） | 节点在位，执行报 A0400「LLM 网关未配置」，不阻塞启动 |
| 生图模型 | `agentflow.nodes.image.api-key` ← `AI_API_KEY`（万相同账号） | 无 key 时 `image.cover` 报不可重试配置错；生图失败本身**降级**为无图继续发布 |
| 成品库目录 | `agentflow.content.library-dir`（默认 `./data/library`） | 启动自检做写探针，不可写直接打错误日志预告 `publish.local`/`xhs.draft` 会失败 |
| 微信凭证 | 环境变量 `WX_API_APPID/WX_API_SECRET`（旧名 `WECHAT_APPID/WECHAT_SECRET` 兼容；容器走 `WECHAT_*_FILE` secret），或 `wechat.draft.credentialId` 指向凭证库 | 库内 AES-GCM 加密存储，API 永不回显明文；未配主密钥时凭证接口报 A0400，其它功能不受影响。**`credentialId` 只认"这条工作流的归属者"**（P7-1）：别人的凭证 id 填进自己的 DSL 会报"凭证不存在或无法解密"且不会出网，这是刻意的——不区分"不存在"与"无权"，免得拿它探测他人凭证 |

生产部署务必：`agentflow.security.enabled=true` + `AGENTFLOW_JWT_SECRET`（≥32B）+ `AGENTFLOW_MASTER_KEY`（base64 32B）。微信通道还需把服务器出网 IP 加进公众号 IP 白名单。

## 6. 已知边界

- `xhs.draft` 无官方公开草稿 API：默认只产出**小红书图文包**（标题/正文/话题/图片清单）落本地成品库；配 `endpoint` 才外发，失败记 warnings 不判 FAILED。
- 热榜源结构变更最易表现为「取到 JSON 但零条目」，节点会显式记 `failedSources`；`endpointOverride` 可换源不改码。
- `copy.write` 输出里的 `parseFallback=true` 表示**模型漏写了 `【正文】` 节**，节点取了回复中最长的一节兜底（标题/摘要这类短节已排除），`parseNote` 会写明来源；这种稿子能发但排版需人工看一眼，`false` 才是按约定分节解析成功的。
- 外链改写链路（`article.extract → … → wechat.draft`）里，草稿的标题/正文**只认创作产物**（带 `body` 的那份上游输出），不会被采集节点的原文标题/原文正文盖掉——这条口径由 `WechatDraftNodeExecutorTest.creationOutputWinsOverIngestNodeWithSameKeys` 钉住（曾经误发原文进草稿箱）。
- `--length` 是给模型的期望值，不是硬阈值：实测 `--length 1500` 交付过 848 字。要"不到字数就重试/标降级"需另加校验。
- `copy.originality` 的分数是**启发式风险信号，不是抄袭的法律判定**：本地口径（5-gram 包含率 + 最长连续公共片段）擅长抓"整段照搬"，模型评分擅长抓"换了说法但观点结构一致"；两者取更严的一侧，但仍需人工看 `similarFragments`。默认只标降级不拦发布，要拦住就 `strict=true`。模型评分缺失（网关没配 / 调用失败 / 报文不是 JSON / 没有 `similarityScore`）同样算降级，输出带 `llmNote` 说明是哪一种，`method=local-only` 的 0 分不等于"校验通过"。
- 排版器只处理**纯文本正文**：`content` 已经是 HTML 时原样透传（不再包装，也不清洗），所以手工粘 HTML 的样式问题不会被节点背锅；正文配图超过 1MB 会被跳过并写进输出的 `warnings`，图少了要在草稿箱里肉眼复核。
- 引擎已支持同层并发（P7-3#18：按依赖分层、同层节点同时跑，宽度 `agentflow.engine.max-parallel-nodes` 默认 8）。本文推荐流水线是 a→b→c→d→e→f→g 单链，所以配图与主图仍逐个产出；想让两者并发就把它们挂成同一上游的两个兄弟节点（下游各自读上游输出，无需改代码）。
- 真 key 联调：注入 `AI_API_KEY`（+ `WX_API_APPID/WX_API_SECRET`）后跑上面第 3 节命令即可。2026-10-25 实测：文案（21.1s）、封面（7.0s，万相真出图）、归档（16ms）三段 SUCCESS；`wechat.draft` 走到了微信服务端并回 `40125 invalid appsecret`——即凭证名已生效、链路已通，剩下的是公众号后台把 AppSecret 配对/重置。
