<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import LoginView from './components/LoginView.vue'
import CanvasView from './components/CanvasView.vue'
import { api, ApiError, getToken, setToken } from './api'

const SESSION_KEY = 'af_session'
const session = ref(readSession())
const workflows = ref([])
const listLoading = ref(false)
const listError = ref('')
const active = ref(null)
const opening = ref(false)

function readSession() {
  if (!getToken()) {
    localStorage.removeItem(SESSION_KEY)
    return null
  }
  try {
    return JSON.parse(localStorage.getItem(SESSION_KEY) || 'null') || { username: '' }
  } catch {
    return { username: '' }
  }
}

function onAuthenticated(s) {
  setToken(s.token)
  const info = { uid: s.uid, username: s.username, role: s.role }
  localStorage.setItem(SESSION_KEY, JSON.stringify(info))
  session.value = info
  active.value = null
  loadWorkflows()
}

function logout() {
  setToken('')
  localStorage.removeItem(SESSION_KEY)
  session.value = null
  active.value = null
  workflows.value = []
}

async function loadWorkflows() {
  if (!session.value) return
  listLoading.value = true
  listError.value = ''
  try {
    const data = await api('/api/workflows')
    workflows.value = data.items || []
  } catch (e) {
    if (e instanceof ApiError && e.code === 'A0401') {
      session.value = null
      return
    }
    listError.value = e.message || String(e)
  } finally {
    listLoading.value = false
  }
}

async function openWorkflow(w) {
  opening.value = true
  listError.value = ''
  try {
    const dsl = await api(`/api/workflows/${w.id}`)
    active.value = { id: w.id, dsl }
  } catch (e) {
    listError.value = e.message || String(e)
  } finally {
    opening.value = false
  }
}

function newWorkflow() {
  active.value = {
    id: null,
    dsl: { name: '未命名工作流', trigger: { type: 'manual' }, nodes: [], edges: [], layout: {} },
  }
}

function closeCanvas() {
  active.value = null
  loadWorkflows()
}

function onSaved() {
  loadWorkflows()
}

function formatTime(value) {
  return value ? String(value).slice(0, 16) : '—'
}

let onUnauthorized = null
onMounted(() => {
  onUnauthorized = () => {
    session.value = null
    active.value = null
  }
  window.addEventListener('af:unauthorized', onUnauthorized)
  loadWorkflows()
})
onBeforeUnmount(() => {
  if (onUnauthorized) window.removeEventListener('af:unauthorized', onUnauthorized)
})
</script>

<template>
  <div v-if="!session" class="app-root">
    <LoginView @authenticated="onAuthenticated" />
  </div>
  <div v-else class="app-root workspace">
    <header class="app-header">
      <div class="logo">agent-flow</div>
      <span class="logo-sub">内容流水线工作台</span>
      <span class="app-header-spacer"></span>
      <span class="user-chip">
        {{ session.username || '已登录' }}
        <em v-if="session.role === 'admin'">admin</em>
      </span>
      <button class="ghost-btn" @click="logout">退出</button>
    </header>
    <main class="workspace-body">
      <CanvasView
        v-if="active"
        :key="active.id || 'new'"
        :workflow-id="active.id"
        :initial-dsl="active.dsl"
        @saved="onSaved"
        @close="closeCanvas"
      />
      <div v-else class="home">
        <section class="home-head">
          <div>
            <h2>我的工作流</h2>
            <p class="muted">一个工作流就是一条可回放的内容流水线；打开画布即可编辑、运行、取成品。</p>
          </div>
          <button class="primary-btn" @click="newWorkflow">+ 新建工作流</button>
        </section>

        <p v-if="listError" class="form-error">{{ listError }}</p>
        <p v-if="opening" class="muted">正在打开画布…</p>

        <div v-if="listLoading" class="muted pad">加载中…</div>
        <div v-else-if="!workflows.length" class="empty-card">
          <h3>还没有工作流</h3>
          <p class="muted">新建后从左侧拖入节点开始搭流水线；画布工具栏里的“载入示例”可一键铺一条热点 → 文案 → 封面 → 归档的示例链路。</p>
          <button class="primary-btn" @click="newWorkflow">新建工作流</button>
        </div>
        <ul v-else class="workflow-grid">
          <li v-for="w in workflows" :key="w.id" class="workflow-card" @click="openWorkflow(w)">
            <div class="workflow-card-head">
              <strong>{{ w.name }}</strong>
              <span class="badge pending">v{{ w.version }}</span>
            </div>
            <div class="muted">{{ w.nodeCount }} 个节点 · #{{ w.id }}</div>
            <div class="muted mono">更新于 {{ formatTime(w.updateTime || w.createdAt) }} · 创建人 {{ w.creator || 'system' }}</div>
            <div class="muted mono">最近修改人 {{ w.updater || w.creator || 'system' }}</div>
            <div class="workflow-card-actions">
              <button class="ghost-btn" @click.stop="openWorkflow(w)">打开画布</button>
            </div>
          </li>
        </ul>
      </div>
    </main>
  </div>
</template>
