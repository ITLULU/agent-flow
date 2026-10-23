<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import LogicFlow, { RectNode, RectNodeModel } from '@logicflow/core'
import '@logicflow/core/lib/style/index.css'
import { api, fetchBlob } from '../api'
import { NODE_CATALOG, catalogOf } from '../nodeCatalog'

const props = defineProps({
  workflowId: { type: [Number, String], default: null },
  initialDsl: { type: Object, required: true },
})
const emit = defineEmits(['saved', 'close'])

const STATUS_COLOR = {
  SUCCESS: '#16a34a',
  DEGRADED: '#d97706',
  FAILED: '#dc2626',
  RUNNING: '#2563eb',
}
const STATUS_LABEL = {
  SUCCESS: '成功',
  SUCCESS_WITH_DEGRADED: '成功（含降级）',
  DEGRADED: '降级',
  FAILED: '失败',
  RUNNING: '运行中',
}
const TERMINAL = ['SUCCESS', 'SUCCESS_WITH_DEGRADED', 'FAILED']

class AfNodeModel extends RectNodeModel {
  setAttributes() {
    this.width = 196
    this.height = 62
    this.radius = 10
  }

  getNodeStyle() {
    const style = super.getNodeStyle()
    const status = this.properties.status
    style.fill = status === 'SUCCESS' ? '#f0fdf4'
      : status === 'FAILED' ? '#fef2f2'
        : status === 'DEGRADED' ? '#fffbeb'
          : status === 'RUNNING' ? '#eff6ff' : '#ffffff'
    style.stroke = STATUS_COLOR[status] || this.properties.color || '#94a3b8'
    style.strokeWidth = status ? 2.5 : 1.5
    return style
  }

  getTextStyle() {
    const style = super.getTextStyle()
    style.color = '#0f172a'
    style.fontSize = 13
    style.fontWeight = 600
    return style
  }
}

const canvasEl = ref(null)
const lf = ref(null)
let disposed = false

const workflowId = ref(props.workflowId)
const name = ref(props.initialDsl?.name || '未命名工作流')
const cron = ref(props.initialDsl?.trigger?.type === 'cron' ? props.initialDsl.trigger.expression || '' : '')
const nextFireAt = ref('')
const selected = ref(null)
const showRaw = ref(false)
const rawJson = ref('')
const saveState = ref('')
const error = ref('')
const toast = ref('')
const running = ref(false)
const execution = ref(null)
const showExec = ref(false)
const artifacts = ref([])

let toastTimer = null
const flash = (text) => {
  toast.value = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 2400)
}
const fail = (e) => {
  error.value = e?.message || String(e)
}

const currentFields = computed(() => (selected.value ? catalogOf(selected.value.type).fields : []))
const execStatusLabel = computed(() => STATUS_LABEL[execution.value?.status] || execution.value?.status || '')

function buildGraph(dsl) {
  const layout = dsl?.layout || {}
  const nodes = (dsl?.nodes || []).map((n, i) => {
    const meta = catalogOf(n.type)
    const pos = layout[n.id]
    return {
      id: n.id,
      type: 'af-node',
      x: pos?.x ?? 180 + (i % 3) * 270,
      y: pos?.y ?? 120 + Math.floor(i / 3) * 150,
      text: meta.label,
      properties: {
        nodeType: n.type,
        params: JSON.parse(JSON.stringify(n.params || {})),
        color: meta.color,
        category: meta.group,
      },
    }
  })
  const edges = (dsl?.edges || []).map((e, i) => ({
    id: `af-e-${i}`,
    type: 'polyline',
    sourceNodeId: e.from,
    targetNodeId: e.to,
  }))
  return { nodes, edges }
}

/**
 * 画布容器尺寸变化后 LogicFlow 只更新画布宽高，不会修正旧的平移量——节点会整体跑到视口外。
 * 每次渲染/尺寸变化都重新适配：先 fitView，内容比容器小时再收回 1:1，避免单节点被放得巨大。
 */
function fitCanvas() {
  const inst = lf.value
  if (!inst) return
  const nodes = inst.getGraphData()?.nodes || []
  if (!nodes.length) return
  inst.fitView(60, 60)
  if (inst.graphModel.transformModel.SCALE_X > 1) {
    inst.zoom(1, [inst.graphModel.width / 2, inst.graphModel.height / 2])
  }
}

function resize() {
  if (!lf.value) return
  lf.value.resize()
  fitCanvas()
}

let resizeObserver = null
onMounted(async () => {
  await nextTick()
  const instance = new LogicFlow({
    container: canvasEl.value,
    grid: { size: 20, visible: true, type: 'dot' },
    background: { backgroundColor: '#f8fafc' },
    snapline: true,
    keyboard: { enabled: true },
    edgeType: 'polyline',
    adjustEdge: true,
    allowRotate: false,
    allowResize: false,
    textEdit: false,
  })
  instance.register({ type: 'af-node', view: RectNode, model: AfNodeModel })
  instance.render(buildGraph(props.initialDsl))
  instance.on('node:click', ({ data }) => selectNode(data.id))
  instance.on('blank:click', () => { selected.value = null })
  lf.value = instance
  fitCanvas()
  resizeObserver = new ResizeObserver(() => {
    requestAnimationFrame(resize)
  })
  resizeObserver.observe(canvasEl.value)
})

onBeforeUnmount(() => {
  disposed = true
  resizeObserver?.disconnect()
  lf.value?.destroy?.()
})

function selectNode(id) {
  const model = lf.value?.getNodeModelById(id)
  if (!model) return
  const p = JSON.parse(JSON.stringify(model.properties || {}))
  selected.value = {
    id,
    type: p.nodeType,
    status: p.status,
    color: p.color,
    category: p.category,
    params: p.params || {},
  }
  rawJson.value = JSON.stringify(selected.value.params || {}, null, 2)
  showRaw.value = false
}

function applyParams() {
  if (!selected.value) return
  lf.value.setProperties(selected.value.id, { params: { ...selected.value.params } })
  rawJson.value = JSON.stringify(selected.value.params || {}, null, 2)
}

function applyRawJson() {
  try {
    const parsed = JSON.parse(rawJson.value || '{}')
    if (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object') {
      throw new Error('params 必须是 JSON 对象')
    }
    selected.value.params = parsed
    applyParams()
    error.value = ''
    flash('参数已应用')
  } catch (e) {
    error.value = `JSON 解析失败：${e.message}`
  }
}

function startDrag(item) {
  lf.value.dnd.startDrag({
    type: 'af-node',
    text: item.label,
    properties: {
      nodeType: item.type,
      params: JSON.parse(JSON.stringify(item.defaults || {})),
      color: item.color,
      category: item.group,
    },
  })
}

function removeSelected() {
  if (!selected.value) return
  const id = selected.value.id
  lf.value.deleteNode(id)
  selected.value = null
  flash(`节点 ${id} 已删除`)
}

function toDsl() {
  const graph = lf.value.getGraphData()
  return {
    name: name.value.trim() || '未命名工作流',
    trigger: cron.value
      ? { type: 'cron', expression: cron.value, timezone: 'Asia/Shanghai' }
      : { type: 'manual' },
    nodes: graph.nodes.map((n) => ({
      id: n.id,
      type: n.properties.nodeType,
      params: n.properties.params || {},
    })),
    edges: graph.edges.map((e) => ({ from: e.sourceNodeId, to: e.targetNodeId })),
    layout: Object.fromEntries(graph.nodes.map((n) => [n.id, { x: Math.round(n.x), y: Math.round(n.y) }])),
  }
}

async function save() {
  error.value = ''
  saveState.value = 'saving'
  try {
    const dsl = toDsl()
    if (!dsl.nodes.length) throw new Error('画布上至少需要一个节点')
    const res = workflowId.value
      ? await api(`/api/workflows/${workflowId.value}`, { method: 'PUT', body: dsl })
      : await api('/api/workflows', { method: 'POST', body: dsl })
    if (!workflowId.value) workflowId.value = res.workflowId
    emit('saved', { id: workflowId.value, name: dsl.name })
    flash('已保存')
    return workflowId.value
  } catch (e) {
    fail(e)
    throw e
  } finally {
    saveState.value = ''
  }
}

async function saveSchedule() {
  error.value = ''
  try {
    const id = await ensureSaved()
    const res = await api(`/api/workflows/${id}/schedule`, {
      method: 'PUT',
      body: { cron: cron.value.trim(), enabled: true },
    })
    nextFireAt.value = res.nextFireAt
    flash('调度已登记')
  } catch (e) {
    fail(e)
  }
}

async function clearSchedule() {
  error.value = ''
  try {
    const id = await ensureSaved()
    await api(`/api/workflows/${id}/schedule`, { method: 'DELETE' })
    cron.value = ''
    nextFireAt.value = ''
    flash('调度已撤销')
  } catch (e) {
    fail(e)
  }
}

async function ensureSaved() {
  return workflowId.value || (await save())
}

async function run() {
  error.value = ''
  try {
    const id = await ensureSaved()
    running.value = true
    showExec.value = true
    execution.value = null
    artifacts.value = []
    const res = await api(`/api/workflows/${id}/executions`, { method: 'POST' })
    pollExecution(res.executionId)
  } catch (e) {
    running.value = false
    fail(e)
  }
}

async function pollExecution(executionId) {
  const deadline = Date.now() + 10 * 60 * 1000
  while (!disposed && Date.now() < deadline) {
    try {
      const view = await api(`/api/executions/${executionId}`)
      if (disposed) return
      execution.value = view
      applyNodeStatuses(view.nodes || [])
      if (TERMINAL.includes(view.status)) {
        running.value = false
        if (view.status !== 'FAILED') await loadArtifacts()
        return
      }
    } catch (e) {
      running.value = false
      fail(e)
      return
    }
    await new Promise((r) => setTimeout(r, 1200))
  }
  if (!disposed) {
    running.value = false
    error.value = '执行查询超时（10 分钟），请刷新后重试'
  }
}

function applyNodeStatuses(logs) {
  for (const log of logs) {
    const model = lf.value?.getNodeModelById(log.nodeId)
    if (model) lf.value.setProperties(log.nodeId, { status: log.status })
  }
}

async function loadArtifacts() {
  try {
    artifacts.value = (await api('/api/artifacts?limit=5')).items || []
  } catch {
    artifacts.value = []
  }
}

async function downloadArtifact(artifact) {
  try {
    const files = await api(`/api/artifacts/${artifact.id}/files`)
    const file = files.find((f) => /\.(md|txt)$/i.test(f)) || files[0]
    if (!file) throw new Error('该成品没有可下载文件')
    const blob = await fetchBlob(`/api/artifacts/${artifact.id}/file?path=${encodeURIComponent(file)}`)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = file.split('/').pop()
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    fail(e)
  }
}

function loadTemplate() {
  const hasNodes = (lf.value.getGraphData().nodes || []).length > 0
  if (hasNodes && !window.confirm('载入示例会替换当前画布（未保存的改动会丢失），继续？')) return
  const demo = {
    name: name.value === '未命名工作流' ? '每日内容工厂' : name.value,
    nodes: [
      { id: 'hot', type: 'hotspot.fetch', params: { sources: 'weibo,baidu,toutiao,zhihu', limit: 10 } },
      { id: 'copy', type: 'copy.write', params: { mode: 'xiaohongshu', topic: '', tone: '真诚分享' } },
      { id: 'cover', type: 'image.cover', params: { ratio: '3:4', kind: 'cover' } },
      { id: 'publish', type: 'publish.local', params: { kind: 'note' } },
    ],
    edges: [
      { from: 'hot', to: 'copy' },
      { from: 'copy', to: 'cover' },
      { from: 'copy', to: 'publish' },
    ],
    layout: {
      hot: { x: 160, y: 120 },
      copy: { x: 430, y: 120 },
      cover: { x: 700, y: 60 },
      publish: { x: 700, y: 190 },
    },
  }
  lf.value.clearData()
  lf.value.render(buildGraph(demo))
  fitCanvas()
  name.value = demo.name
  flash('示例流水线已载入，点"运行"看执行时间线')
}

const groups = computed(() => {
  const map = new Map()
  for (const item of NODE_CATALOG) {
    if (!map.has(item.group)) map.set(item.group, [])
    map.get(item.group).push(item)
  }
  return [...map.entries()]
})
</script>

<template>
  <section class="canvas-shell">
    <header class="canvas-toolbar">
      <button class="ghost-btn" title="返回工作流列表" @click="emit('close')">← 返回</button>
      <input v-model="name" class="workflow-name" aria-label="工作流名称" />
      <span class="toolbar-spacer"></span>
      <button class="ghost-btn" :disabled="running" @click="loadTemplate">载入示例</button>
      <button class="ghost-btn" :disabled="saveState === 'saving'" @click="save">
        {{ saveState === 'saving' ? '保存中…' : '保存' }}
      </button>
      <button class="primary-btn" :disabled="running" @click="run">
        {{ running ? '执行中…' : '运行' }}
      </button>
    </header>

    <div class="canvas-body">
      <aside class="palette">
        <div class="palette-title">节点面板</div>
        <p class="palette-tip">拖到画布，拖动锚点连线</p>
        <div v-for="[group, items] in groups" :key="group" class="palette-group">
          <div class="palette-group-name">{{ group }}</div>
          <div
            v-for="item in items"
            :key="item.type"
            class="palette-item"
            :style="{ borderLeftColor: item.color }"
            :title="item.desc"
            @mousedown.prevent="startDrag(item)"
          >
            <span class="palette-item-label">{{ item.label }}</span>
            <span class="palette-item-type">{{ item.type }}</span>
          </div>
        </div>
      </aside>

      <div ref="canvasEl" class="lf-canvas"></div>

      <aside v-if="selected" class="inspector">
        <div class="inspector-head">
          <div>
            <div class="inspector-title">{{ catalogOf(selected.type).label }}</div>
            <div class="inspector-sub">{{ selected.type }} · {{ selected.id }}</div>
          </div>
          <button class="icon-btn" title="关闭" @click="selected = null">×</button>
        </div>
        <div class="inspector-status">
          <span class="badge" :class="(selected.status || 'PENDING').toLowerCase()">
            {{ STATUS_LABEL[selected.status] || '待执行' }}
          </span>
          <span class="muted">{{ catalogOf(selected.type).desc }}</span>
        </div>
        <div class="inspector-form">
          <label v-for="field in currentFields" :key="field.key">
            <span>{{ field.label }}</span>
            <select
              v-if="field.type === 'select'"
              v-model="selected.params[field.key]"
              @change="applyParams"
            >
              <option v-for="opt in field.options" :key="opt" :value="opt">{{ opt }}</option>
            </select>
            <textarea
              v-else-if="field.type === 'textarea'"
              v-model="selected.params[field.key]"
              rows="3"
              :placeholder="field.placeholder || ''"
              @change="applyParams"
            ></textarea>
            <input
              v-else-if="field.type === 'number'"
              v-model.number="selected.params[field.key]"
              type="number"
              :placeholder="field.placeholder || ''"
              @change="applyParams"
            />
            <input
              v-else
              v-model="selected.params[field.key]"
              type="text"
              :placeholder="field.placeholder || ''"
              @change="applyParams"
            />
          </label>

          <details class="raw-params" :open="showRaw" @toggle="showRaw = $event.target.open">
            <summary>高级：params JSON</summary>
            <textarea v-model="rawJson" rows="8"></textarea>
            <button class="ghost-btn" @click="applyRawJson">应用 JSON</button>
          </details>

          <button class="danger-btn" @click="removeSelected">删除节点</button>
        </div>
      </aside>

      <div class="schedule-bar">
        <span class="muted bar-label">调度</span>
        <input v-model.trim="cron" placeholder="cron: 0 0 8 * * *" />
        <button class="ghost-btn" @click="saveSchedule">登记</button>
        <button class="ghost-btn" @click="clearSchedule">撤销</button>
        <span v-if="nextFireAt" class="muted next-fire">下次：{{ nextFireAt }}</span>
      </div>

      <div v-if="error" class="canvas-error">
        <span>{{ error }}</span>
        <button class="icon-btn" @click="error = ''">×</button>
      </div>
      <div v-if="toast" class="toast">{{ toast }}</div>

      <div v-if="showExec" class="exec-drawer">
        <div class="exec-head">
          <div>
            <strong>执行时间线</strong>
            <span v-if="execution" class="muted">&nbsp;#{{ execution.id }} · trace {{ execution.traceId?.slice(0, 12) }}</span>
          </div>
          <div class="exec-head-right">
            <span v-if="execution" class="badge" :class="(execution.status || '').toLowerCase()">
              {{ execStatusLabel }}
            </span>
            <button class="icon-btn" @click="showExec = false">×</button>
          </div>
        </div>
        <div v-if="!execution" class="muted pad">正在创建执行…</div>
        <ul v-else class="exec-list">
          <li v-for="log in execution.nodes" :key="log.nodeId" class="exec-row">
            <span class="badge" :class="(log.status || '').toLowerCase()">{{ STATUS_LABEL[log.status] || log.status }}</span>
            <span class="exec-node">{{ catalogOf(log.nodeType).label }}</span>
            <span class="muted">{{ log.nodeId }}</span>
            <span class="muted">{{ log.attempts }} 次 / {{ log.durationMs }}ms</span>
            <span v-if="log.reason" class="exec-reason">{{ log.reason }}</span>
          </li>
          <li v-if="execution.nodes.length === 0" class="muted pad">等待第一个节点落日志…</li>
        </ul>
        <div v-if="artifacts.length" class="artifact-strip">
          <span class="muted">最近成品：</span>
          <button v-for="a in artifacts" :key="a.id" class="artifact-chip" @click="downloadArtifact(a)">
            #{{ a.id }} {{ a.title || a.kind }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
