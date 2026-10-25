# 小红书图文创作风格手册（RED Style Guide）

> 基于对 2024–2026 热门图文博主 plog 的分析，归纳文案创作规律、配图设计语言、拼图排版技巧与"不像 AI 生成"的真实感策略。
> 适用：`copy.write(mode=xiaohongshu)` 提示词优化 + `image.cover` 生图提示词优化。

## 一、文案创作规律

### 1.1 标题公式（5 大类，每种给出 3 个实例）

**公式一：数字 + 结果**
- "3 个方法让早八人 10 分钟出门"
- "30 岁后才懂的 5 条消费观"
- "存够这 4 笔钱，裸辞也不慌"

**公式二：痛点 + 解决方案**
- "一到下午就犯困？不是你没睡够"
- "记账记了 3 年越记越穷？问题出在这里"
- "为什么你拍的照片总像游客照"

**公式三：身份标签 + 场景**
- "INFP 打工人_周一早上的工位实录"
- "二胎妈妈的书桌｜偷来的 1 小时"
- "独居女生的周末_一人一猫一壶茶"

**公式四：反差 + 悬念**
- "我关掉朋友圈的第 100 天，收到了 3 条微信"
- "月薪 8k 的我，3 年存了 50w（不是靠省）"
- "看起来很贵，其实花了不到 100 块"

**公式五：时间限定 + 紧迫感**
- "2025 最后 30 天，做完这 5 件事"
- "周六早晨 7 点，我做了 1 个改变一切的决定"
- "距离 30 岁还有 3 个月，我停了所有无效社交"

### 1.2 正文结构（小红书独有节奏）

| 位置 | 作用 | 字数 | 技巧 |
|---|---|---|---|
| 开头 2 行 | 钩子，让人点"展开" | ≤40 字 | 痛点前置 / 反常识结论 / 强烈情绪 |
| 正文要点 | 3~5 个，每点先结论再展开 | 每点 80~150 字 | 用 `·` 或 `•` 分点，不用 `1. 2.` |
| 结尾 | 互动引导 | ≤30 字 | 提问式（"你也有过吗？"），不用"点赞收藏" |
| 标签 | 5~8 个 | — | 大流量词 + 长尾词，话题要具体 |

### 1.3 口语化与人设感（区别"AI 写作"的关键）

- **第一人称叙事**：用"我"开头，讲"我上周"、"我试了 3 次"（真实体验感）
- **语气词与停顿**："就……"、"真的很……"、"说实话"、"啊对对对"
- **场景锚点**：具体时间/地点/道具（"周二下午 3 点，对着电脑发了 20 分钟呆"）
- **适度脆弱感**：不说完全正确的话，承认"我也在试"、"不是每次都成功"
- **emoji 规则**：每 2~3 行 1 个，不超过 15 个/篇；emoji 用于分段/强调/情绪，不堆砌
- **禁止 AI 味表达**：避免"综上所述"、"值得注意的是"、"首先……其次……最后"、"在当今社会"；避免所有段落等长（3 行/2 行/4 行交错）
- **用网络口语**："真的会谢"、"纯纯的"、"谁懂啊"、"谁用谁知道"、"救命"（适度，每篇 1~2 次）

### 1.4 20 种爆款风格速查

种草、干货、反差、情绪、搞笑、故事、对比、揭秘、清单、记录、新手向、低成本、挑战、避雷、情境代入、人格分裂体、反套路、打脸、氛围感、碎片随拍

---

## 二、配图设计语言

### 2.1 "不像 AI"的核心策略

| 问题 | AI 生成常见表现 | 规避方法 |
|---|---|---|
| 皮肤质感 | 塑料感、过度光滑、无毛孔 | 提示词加 "film grain, natural skin texture, slight imperfections" |
| 光影 | 均匀无影、AI 特有的梦幻柔光 | 加 "harsh natural light, window light, one directional shadow" |
| 构图 | 完美居中、对称、无杂物 | 加 "candid shot, slightly off-center, cluttered desk, lived-in feel" |
| 文字/UI | 乱码、伪中文、不可读 | 不加文字，留白给后期加字 |
| 比例怪异 | 手指错误、物体悬浮 | 加 "phone in hand, holding object, natural grip" |
| 景深 | 全画面锐利 | 加 "shallow depth of field, bokeh background, f/2.8" |

**关键结论**：AI 图要过小红书"真实感"这关，必须模拟**手机摄影**的质感——噪点、非对称构图、生活杂物、自然光阴影、浅景深。目前最接近的方案是**万相 v2.1 加摄影风格的负向提示词**（见 §2.3）。

### 2.2 热门 plog 视觉风格清单

| 风格名 | 色调 | 特征 | 适用选题 |
|---|---|---|---|
| 日系胶片 | 低饱和、偏青/黄、褪色 | 颗粒感、白边框、日期戳 | 生活记录、日常 vlog |
| ins 韩系 | 高亮、低对比、冷白 | 极简构图、大量留白、无字 | 穿搭、美妆、书桌 |
| 暗调氛围 | 深色背景、单束光源 | 暖灯、书籍、咖啡 | 夜晚思考、阅读、效率 |
| 多巴胺 | 高饱和撞色 | 彩色卡片、涂鸦、手写字体 | 清单、教程、干货 |
| 素人实拍 | 无滤镜、自然光 | 手机平拍、桌面杂物入镜 | 好物分享、开箱、测评 |
| 复古拼贴 | 牛皮纸、拍立得边、胶带 | 手撕边、重叠、便签 | 读书笔记、周计划、plog |

### 2.3 万相 prompt 策略（分场景）

**XHS 主图（3:4 竖版）**：
```
A phone-shot lifestyle photo, vertical 3:4, natural window light from the left,
[主题描述], clutterd desk with [道具1] and [道具2], shallow depth of field f/2.8,
film grain, candid composition, slightly warm tone, lived-in aesthetic,
no text overlay, no watermarks, no AI smoothness --ar 3:4
```

**XHS 配图（1:1 方形）**：
```
Flat lay overhead shot, [物品] arranged on [表面材质], natural light,
minimalist composition but not sterile, phone camera style, slight shadow,
texture visible, no text, no watermark --ar 1:1
```

**公众号主图（2.35:1 横版封面）**：
```
Editorial hero image, wide 2.35:1, [主题意境], atmospheric lighting,
cinematic composition, negative space on right for text overlay,
photorealistic but artistic, no watermarks --ar 21:9
```

**公众号章节图（1:1 或 4:3）**：
```
Abstract conceptual image, [章节主题], soft gradient background,
single focal object at center, minimalist, suitable for text overlay on top,
no watermarks --ar 4:3
```

### 2.4 四宫格拼图规范

- **尺寸**：整体 1080×1080px（小红书标准），四格各 540×540px
- **边框**：2~4px 白边（模拟手机截屏拼图质感）
- **风格统一**：四格必须是同一色调/滤镜/时间段的照片
- **构图节奏**：1 张近景特写 + 1 张中景场景 + 1 张远景氛围 + 1 张文字/引用卡片
- **常见主题**：一周穿搭 ×4、书桌四时、before/after ×2、四步教程

---

## 三、落地对照（本手册 → 代码）

下面是"手册里写的"与"仓库里真实存在的"逐项对照，**没做的写没做**，免得后来人按清单找不到文件。

### 3.1 文案提示词 `copy-xiaohongshu.txt` ✅ 已改
- 标题公式改成 5 类具体公式 + 实例；正文结构改成"钩子开头 → 3~5 个 `·` 分点 → 互动提问结尾"
- 加了"AI 味禁止清单"（综上所述 / 首先其次 / 值得注意的是 …）与口语化要求（第一人称、语气词、场景锚点、脆弱感、句长错落）
- emoji 上限 15/篇、每段至少 1 个；标签给了大流量词 + 长尾词组合示例
- 路径：`server/agent-flow-server/node-adapters/src/main/resources/prompts/copy-xiaohongshu.txt`

### 3.2 `CoverImageNodeExecutor` ✅ 已增强（与手册的差异见下）
- `scene`：`xhs-cover` / `xhs-body` / `wechat-cover` / `wechat-chapter`，自动定比例 + 加载对应骨架模板 ✅
- `promptStyle`：`film` / `street` / `minimal` / `warm` / `dark` / `vintage` 六种预设英文风格词 ✅（手册里写的 `collage` 不是一种画风，已剔除）
- `negativePrompt`：默认反 AI 味负向词，随请求发 `input.negative_prompt` ✅
- `referenceStyle`：参考图风格配方（手册没写，实现时补的——"照参考图换内容"缺它就落不了地）✅
- `collage`：**只实现了 `2x2`**，手册里的 `1x3` / `2x3` 未做 ⛔（四宫格是小红书主流版式，先把它做扎实；拼接由 `ImageStitcher` 本地完成，不需要提示词模板，所以手册里列的 `prompts/image-collage.txt` **不存在也不该存在**）
- 拼图失败不阻断单张，但显性回 `collageError`（`CoverImageNodeExecutorTest.collageFailureIsReportedNotSwallowed` 钉住）

### 3.3 新增文件（实际路径）
- `docs/RED-STYLE-GUIDE.md`（本文档）
- `node-adapters/src/main/resources/prompts/image/{xhs-cover,xhs-body,wechat-cover,wechat-chapter}.txt`（四个场景骨架；注意在 `prompts/image/` 子目录，不是手册原先写的 `prompts/image-*.txt`）
- `node-adapters/src/main/resources/prompts/image/reference-analysis.txt`（参考图 → 风格配方的视觉模型提示词）
- `node-adapters/src/main/java/com/agentflow/nodes/image/ImageStitcher.java`（2×2 拼接，3px 白缝，等比居中裁切）
- `node-adapters/src/main/java/com/agentflow/nodes/image/ReferenceStyleAnalyzer.java`（qwen-vl 多模态解析参考图）
- `server-application/src/main/java/com/agentflow/server/web/ImageStudioController.java`（`/api/image/studio/{generate,analyze-reference,file}`）
- `web/agent-image-studio/**`（**独立生图前端项目**：`index.html` + `src/{main.js,App.vue,api.js,studio.css}`，dev 端口 5174、`/api` 代理 8080；参考图定风格、内容换自己的、按场景出尺寸、四宫格同时给拼图和 4 张单图）
- `scripts/run-server.sh`（本地后端启动/重启：端口占用与缺 env 前置检查，不接触、不回显任何密钥值）
- `web/agent-flow-web/src/nodeCatalog.js`（画布参数面板同步 scene/promptStyle/collage/referenceStyle/negativePrompt；`npm run check:catalog` 校验字段名与执行器不漂移）
- 测试：`CoverImageNodeExecutorTest`（17）/ `ReferenceStyleAnalyzerTest`（7）/ `ImageStitcherTest`（3）

用法与参数口径见 `docs/CREATION-GUIDE.md` §2.0.1–§2.0.2。


---

## 四、参考资料

- 小红书爆款风向标 (Canva): https://www.canva.cn/zh_cn/work-kits/rednote-viral-templates/
- 小红书标题 5 大公式: https://help.reditorapp.com/content/260313
- 20 种小红书爆款文案风格: https://jiandan.link/blog/小红书运营指南：如何制作小红书爆款文案/
- 10 大万能结构公式: https://zhuanlan.zhihu.com/p/1987572740459364919
- 四宫格封面排版技巧: https://www.designkit.cn/article/xiaohongshusigonggefengmian20250529
- 2025 合规用 AI 辅助创作: https://zhuanlan.zhihu.com/p/1944415969956066834
- 小红书拒绝 AI（平台态度）: https://www.tmtpost.com/7914453.html