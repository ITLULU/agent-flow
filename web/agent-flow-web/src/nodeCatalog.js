/**
 * 画布节点目录：label/颜色/默认参数/表单字段。
 * 参数名与 node-adapters 中的 paramAsString 严格对齐；未列出的参数走"高级 JSON"编辑。
 * 这里刻意是静态目录而不是后端 schema 接口：节点集是编译期固定的，静态度量更小、离线可用；
 * 新增节点时必须同步这里，`nodeCatalog.test.js` 之外由 P4 画布 E2E 兜底。
 */
export const NODE_CATALOG = [
  {
    type: 'hotspot.fetch',
    label: '热点抓取',
    group: '采集',
    color: '#0f766e',
    desc: '微博/百度/头条/知乎热榜合并去重；单源失败不拖垮整体',
    defaults: { sources: 'weibo,baidu,toutiao,zhihu', limit: 20 },
    fields: [
      { key: 'sources', label: '来源（逗号分隔）', type: 'text' },
      { key: 'limit', label: '合并后条数', type: 'number' },
    ],
  },
  {
    type: 'article.extract',
    label: '正文抽取',
    group: '采集',
    color: '#0ea5e9',
    desc: '按 URL 抓取正文并自动抽标题',
    defaults: { url: '', selector: '' },
    fields: [
      { key: 'url', label: '文章 URL', type: 'text', placeholder: 'https://...' },
      { key: 'selector', label: '正文选择器（可选）', type: 'text', placeholder: 'article, .content' },
    ],
  },
  {
    type: 'http.collect',
    label: 'HTTP 采集',
    group: '采集',
    color: '#38bdf8',
    desc: '通用 GET/POST 取数，支持 CSS 选择器抽字段',
    defaults: { url: '', method: 'GET', cssSelector: '', attr: '' },
    fields: [
      { key: 'url', label: 'URL', type: 'text' },
      { key: 'method', label: '方法', type: 'select', options: ['GET', 'POST'] },
      { key: 'cssSelector', label: 'CSS 选择器（可选）', type: 'text' },
      { key: 'attr', label: '取属性（可选）', type: 'text', placeholder: 'href / text' },
    ],
  },
  {
    type: 'llm.chat',
    label: 'LLM 对话',
    group: '创作',
    color: '#7c3aed',
    desc: '通用大模型调用（默认 qwen-plus）',
    defaults: { system: '', prompt: '', model: 'qwen-plus' },
    fields: [
      { key: 'model', label: '模型', type: 'text' },
      { key: 'system', label: 'System', type: 'textarea' },
      { key: 'prompt', label: 'Prompt', type: 'textarea' },
    ],
  },
  {
    type: 'copy.write',
    label: '文案成稿',
    group: '创作',
    color: '#a855f7',
    desc: '按公众号/小红书口径产出标题、正文、标签',
    defaults: { mode: 'wechat', topic: '', audience: '', tone: '', extra: '', model: 'qwen-plus' },
    fields: [
      { key: 'mode', label: '渠道', type: 'select', options: ['wechat', 'xiaohongshu'] },
      { key: 'topic', label: '选题', type: 'text' },
      { key: 'audience', label: '目标读者', type: 'text' },
      { key: 'tone', label: '语气风格', type: 'text' },
      { key: 'extra', label: '补充要求', type: 'textarea' },
      { key: 'model', label: '模型', type: 'text' },
    ],
  },
  {
    type: 'image.cover',
    label: '封面主图',
    group: '创作',
    color: '#db2777',
    desc: '按比例出封面，失败降级为无图（不阻断发布）',
    defaults: { ratio: '3:4', kind: 'cover', prompt: '', topic: '', style: '', model: 'wanx2.1-t2i-turbo' },
    fields: [
      { key: 'ratio', label: '比例', type: 'text', placeholder: '3:4 / 16:9 / 1:1' },
      { key: 'kind', label: '用途', type: 'select', options: ['cover', 'inline'] },
      { key: 'prompt', label: '画面提示词', type: 'textarea' },
      { key: 'topic', label: '主题（缺省取上游）', type: 'text' },
      { key: 'style', label: '风格', type: 'text' },
      { key: 'model', label: '模型', type: 'text' },
    ],
  },
  {
    type: 'image.wanxiang',
    label: '万相生图',
    group: '创作',
    color: '#f472b6',
    desc: '异步任务轮询出图，返回 imageUrl',
    defaults: { prompt: '', model: 'wanx2.1-t2i-turbo', size: '1024*1024' },
    fields: [
      { key: 'prompt', label: '提示词', type: 'textarea' },
      { key: 'model', label: '模型', type: 'text' },
      { key: 'size', label: '尺寸', type: 'text', placeholder: '1024*1024' },
    ],
  },
  {
    type: 'publish.local',
    label: '成品归档',
    group: '发布',
    color: '#16a34a',
    desc: 'article.md/note.txt + meta.json + assets 落成品库',
    defaults: { kind: 'article', title: '', body: '' },
    fields: [
      { key: 'kind', label: '成品类型', type: 'select', options: ['article', 'note'] },
      { key: 'title', label: '标题（缺省取上游）', type: 'text' },
      { key: 'body', label: '正文（缺省取上游）', type: 'textarea' },
    ],
  },
  {
    type: 'xhs.draft',
    label: '小红书草稿',
    group: '发布',
    color: '#e11d48',
    desc: '默认只产出图文包；配置 endpoint 才外发',
    defaults: { title: '', body: '', tags: '', endpoint: '', accessToken: '', credentialId: '' },
    fields: [
      { key: 'title', label: '标题', type: 'text' },
      { key: 'body', label: '正文', type: 'textarea' },
      { key: 'tags', label: '标签（逗号分隔）', type: 'text' },
      { key: 'endpoint', label: '外发端点（可选）', type: 'text' },
      { key: 'accessToken', label: 'Access Token（可选）', type: 'text' },
      { key: 'credentialId', label: '凭证 ID（可选）', type: 'text' },
    ],
  },
  {
    type: 'wechat.draft',
    label: '公众号草稿',
    group: '发布',
    color: '#059669',
    desc: 'token → 封面素材 → draft/add；失败不降级',
    defaults: { title: '', content: '', digest: '', thumbUrl: '', author: '', credentialId: '' },
    fields: [
      { key: 'title', label: '标题', type: 'text' },
      { key: 'content', label: '正文', type: 'textarea' },
      { key: 'digest', label: '摘要', type: 'text' },
      { key: 'thumbUrl', label: '封面图 URL', type: 'text' },
      { key: 'author', label: '作者', type: 'text' },
      { key: 'credentialId', label: '凭证 ID（可选）', type: 'text' },
    ],
  },
  {
    type: 'core.echo',
    label: '调试回声',
    group: '调试',
    color: '#64748b',
    desc: '原样返回 message，用于验证连线与触发',
    defaults: { message: 'hello' },
    fields: [{ key: 'message', label: 'message', type: 'text' }],
  },
]

export const NODE_BY_TYPE = Object.fromEntries(NODE_CATALOG.map((n) => [n.type, n]))

export function catalogOf(type) {
  return NODE_BY_TYPE[type] || {
    type,
    label: type,
    group: '未知',
    color: '#64748b',
    desc: '未登记的节点类型',
    fields: [],
  }
}
