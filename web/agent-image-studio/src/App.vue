<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api, ApiError, getToken, setToken, login, fetchBlob } from './api.js'

const SCENES = [
  { key: 'xhs-cover', label: '小红书主图', ratio: '3:4', hint: '信息流首图，竖构图最抓眼' },
  { key: 'xhs-body', label: '小红书配图', ratio: '1:1', hint: '正文方图，平铺/细节通用' },
  { key: 'wechat-cover', label: '公众号主图', ratio: '2.35:1', hint: '分享大图，横幅留白' },
  { key: 'wechat-chapter', label: '公众号章节图', ratio: '4:3', hint: '小标题配图，能压字' },
]

const STYLES = [
  { key: 'film', label: '胶片', hint: 'Portra 400 色调 + 颗粒' },
  { key: 'street', label: '街拍', hint: '硬光随手拍 + 城市纹理' },
  { key: 'minimal', label: '极简', hint: '大留白 + 干净线条' },
  { key: 'warm', label: '暖光', hint: '黄金时刻 + 柔和阴影' },
  { key: 'dark', label: '暗调', hint: '低键光 + 单光源' },
  { key: 'vintage', label: '复古刊物', hint: '褪色 + 纸纹 + 90s' },
]

const DEFAULT_NEGATIVE =
  'plastic skin, waxy face, AI smoothness, perfect symmetry, studio lighting, uncanny valley, ' +
  'text watermark, Chinese characters, signature, extra fingers, bad anatomy, fused body parts, cloned face, low quality, blurry'

const session = ref(getToken() ? { username: localStorage.af_studio_user || '' } : null)
const username = ref('')
const password = ref('')
const authError = ref('')
const authBusy = ref(false)

const scene = ref('xhs-cover')
const topic = ref('')
const styleKey = ref('film')
const collage = ref(true)
const count = ref(2)
const negativePrompt = ref(DEFAULT_NEGATIVE)
const model = ref('wanx2.1-t2i-turbo')
const rawPrompt = ref(false)
const advanced = ref(false)

const refImage = ref(null) // { name, dataUrl }
const refFields = ref([]) // [{key,label,value}]
const stylePrompt = ref('')
const analyzing = ref(false)
const analyzeError = ref('')

const generating = ref(false)
const error = ref('')
const result = ref(null)
const objectUrls = []
const busyText = ref('')

async function doLogin() {
  if (!username.value || !password.value) {
    authError.value = '请输入用户名和密码'
    return
  }
  authBusy.value = true
  authError.value = ''
  try {
    const s = await login(username.value, password.value)
    setToken(s.token)
    localStorage.setItem('af_studio_user', s.username || username.value)
    session.value = { username: s.username || username.value }
  } catch (e) {
    authError.value = e.message || String(e)
  } finally {
    authBusy.value = false
  }
}

function logout() {
  setToken('')
  localStorage.removeItem('af_studio_user')
  session.value = null
}

function onUnauthorized() {
  session.value = null
}

function onPickFile(evt) {
  const file = evt.target.files && evt.target.files[0]
  if (file) readImage(file)
}

function readImage(file) {
  if (!/^image\//.test(file.type)) {
    analyzeError.value = '只能选图片文件'
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    analyzeError.value = '参考图请压到 2MB 以内（当前 ' + Math.round(file.size / 1024) + 'KB）'
    return
  }
  analyzeError.value = ''
  const reader = new FileReader()
  reader.onload = () => {
    refImage.value = { name: file.name, dataUrl: String(reader.result) }
    refFields.value = []
    stylePrompt.value = ''
  }
  reader.onerror = () => {
    analyzeError.value = '读取文件失败'
  }
  reader.readAsDataURL(file)
}

function onPaste(evt) {
  const item = Array.from(evt.clipboardData?.items || []).find((i) => i.type.startsWith('image/'))
  if (item) {
    const file = item.getAsFile()
    if (file) readImage(file)
  }
}

async function analyzeReference() {
  if (!refImage.value) {
    analyzeError.value = '先选一张参考图'
    return
  }
  analyzing.value = true
  analyzeError.value = ''
  try {
    const data = await api('/api/image/studio/analyze-reference', {
      method: 'POST',
      body: { image: refImage.value.dataUrl },
    })
    refFields.value = data.fields || []
    stylePrompt.value = data.stylePrompt || ''
  } catch (e) {
    analyzeError.value = e.message || String(e)
  } finally {
    analyzing.value = false
  }
}

function clearReference() {
  refImage.value = null
  refFields.value = []
  stylePrompt.value = ''
  analyzeError.value = ''
}

function fieldHint(key) {
  return {
    subject: '机位与视角，例：俯拍 45°、平视半身',
    composition: '构图与留白，例：主体偏右下，上方三分之二留白',
    palette: '具体色名与占比，例：奶油白 60% + 陶土橘 30%',
    light: '光从哪来、软硬，例：侧窗柔光、硬直射',
    texture: '材质与颗粒，例：细胶片颗粒、哑光纸面',
    mood: '情绪，例：松弛、克制、热闹',
    layout: '版式，例：白边框 + 手写标注',
    antiAi: '最关键的"不像 AI"证据，生成时会被逐条喂进提示词',
  }[key] || ''
}

async function generate() {
  if (!topic.value.trim() && !stylePrompt.value.trim()) {
    error.value = '至少填"我要生成的内容"'
    return
  }
  generating.value = true
  error.value = ''
  result.value = null
  releasePreviews()
  busyText.value = collage.value ? '四宫格出图较慢（4 张 + 拼接），最长约 90 秒…' : '正在出图，最长约 90 秒…'
  try {
    const body = {
      scene: scene.value,
      topic: topic.value.trim(),
      style: styleKey.value,
      referenceStyle: stylePrompt.value.trim(),
      negativePrompt: negativePrompt.value.trim(),
      model: model.value.trim(),
      n: collage.value ? 4 : count.value,
      collage: collage.value ? '2x2' : '',
      raw: rawPrompt.value,
    }
    const data = await api('/api/image/studio/generate', { method: 'POST', body })
    result.value = data
    await loadPreviews(data)
  } catch (e) {
    error.value = e instanceof ApiError && e.code === 'A0401' ? '登录已过期，请重新登录' : e.message || String(e)
  } finally {
    generating.value = false
    busyText.value = ''
  }
}

async function loadPreviews(data) {
  const items = [...(data.images || [])]
  if (data.collage && data.collage.downloadUrl) {
    items.unshift({ ...data.collage, isCollage: true })
  }
  for (const item of items) {
    if (!item.downloadUrl) {
      continue
    }
    try {
      const blob = await fetchBlob(item.downloadUrl)
      const url = URL.createObjectURL(blob)
      objectUrls.push(url)
      item.previewUrl = url
    } catch (e) {
      item.previewUrl = ''
      item.previewError = e.message || '取图失败'
    }
  }
}

function releasePreviews() {
  objectUrls.splice(0).forEach((u) => URL.revokeObjectURL(u))
}

function download(item) {
  if (!item.previewUrl) {
    return
  }
  const a = document.createElement('a')
  a.href = item.previewUrl
  a.download = item.fileName || 'image.png'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

function downloadAll() {
  if (!result.value) return
  const tiles = (result.value.images || []).filter((i) => i.previewUrl)
  const collageItem = result.value.collage && result.value.collage.previewUrl ? [result.value.collage] : []
  ;[...collageItem, ...tiles].forEach((item, idx) => setTimeout(() => download(item), idx * 300))
}

onMounted(() => {
  window.addEventListener('paste', onPaste)
  window.addEventListener('af:unauthorized', onUnauthorized)
})
onBeforeUnmount(() => {
  window.removeEventListener('paste', onPaste)
  window.removeEventListener('af:unauthorized', onUnauthorized)
  releasePreviews()
})
</script>

<template>
  <div v-if="!session" class="studio-login">
    <div class="studio-login-card">
      <h1>agent-flow 生图工作台</h1>
      <p class="muted">参考图定风格，内容换自己的，尺寸按渠道自动配。</p>
      <label><span>用户名</span><input v-model="username" autofocus @keyup.enter="doLogin" /></label>
      <label><span>密码</span><input v-model="password" type="password" @keyup.enter="doLogin" /></label>
      <p v-if="authError" class="form-error">{{ authError }}</p>
      <button class="primary-btn wide" :disabled="authBusy" @click="doLogin">{{ authBusy ? '登录中…' : '进入工作台' }}</button>
    </div>
  </div>

  <div v-else class="studio-root">
    <header class="studio-head">
      <div>
        <strong>生图工作台</strong>
        <span class="muted">参考图风格 → 换成我的内容 → 按渠道尺寸出图</span>
      </div>
      <span class="head-spacer"></span>
      <span class="user-chip">{{ session.username || '已登录' }}</span>
      <button class="ghost-btn" @click="logout">退出</button>
    </header>

    <main class="studio-body">
      <section class="studio-form">
        <div class="studio-block">
          <h3>1. 用在哪里（决定尺寸）</h3>
          <div class="scene-grid">
            <button
              v-for="s in SCENES"
              :key="s.key"
              class="scene-card"
              :class="{ active: scene === s.key }"
              @click="scene = s.key"
            >
              <span class="scene-ratio" :style="{ aspectRatio: s.ratio.replace(':', '/') }"></span>
              <strong>{{ s.label }}</strong>
              <em>{{ s.ratio }}</em>
              <p>{{ s.hint }}</p>
            </button>
          </div>
        </div>

        <div class="studio-block">
          <h3>2. 参考图（可选，也可以直接 Cmd+V 粘贴）</h3>
          <div class="ref-row">
            <input type="file" accept="image/*" @change="onPickFile" />
            <button class="ghost-btn" :disabled="analyzing || !refImage" @click="analyzeReference">
              {{ analyzing ? '正在拆风格…' : '解析这张图的风格' }}
            </button>
            <button class="ghost-btn" :disabled="!refImage" @click="clearReference">清除</button>
          </div>
          <div v-if="refImage" class="ref-preview">
            <img :src="refImage.dataUrl" alt="参考图" />
            <div class="ref-name muted">{{ refImage.name }}</div>
          </div>
          <p v-if="analyzeError" class="form-error">{{ analyzeError }}</p>

          <div v-if="refFields.length" class="ref-fields">
            <label v-for="f in refFields" :key="f.key" class="ref-field">
              <span>{{ f.label }}</span>
              <textarea v-model="f.value" rows="2"></textarea>
              <em class="muted">{{ fieldHint(f.key) }}</em>
            </label>
          </div>
          <label v-if="refFields.length || stylePrompt" class="ref-field">
            <span>喂给生图模型的风格提示词（可手改）</span>
            <textarea v-model="stylePrompt" rows="3"></textarea>
            <em class="muted">这里是"雷同风格"的真正来源；参考图本身不会进模型，模型只看这段英文。</em>
          </label>
        </div>

        <div class="studio-block">
          <h3>3. 我要生成的内容</h3>
          <textarea v-model="topic" rows="3" placeholder="例：周末在家做的一锅番茄牛腩，窗边拍摄，餐桌有点乱但很生活"></textarea>
          <div class="toggle-row">
            <label class="check"><input v-model="rawPrompt" type="checkbox" /><span>完全自定义提示词（跳过场景模板与风格配方）</span></label>
          </div>
        </div>

        <div class="studio-block">
          <h3>4. 画风 / 氛围</h3>
          <div class="style-chips">
            <button
              v-for="s in STYLES"
              :key="s.key"
              class="style-chip"
              :class="{ active: styleKey === s.key }"
              :title="s.hint"
              @click="styleKey = styleKey === s.key ? '' : s.key"
            >
              {{ s.label }}
            </button>
          </div>
          <p class="muted small">{{ (STYLES.find((s) => s.key === styleKey) || {}).hint || '未选画风：只用参考图配方 + 场景骨架' }}</p>
        </div>

        <div class="studio-block">
          <h3>5. 出几张 / 要不要四宫格</h3>
          <div class="toggle-row">
            <label class="check">
              <input v-model="collage" type="checkbox" />
              <span>拼成四宫格（同时给我拼图和 4 张单图）</span>
            </label>
          </div>
          <label v-if="!collage" class="inline-field">
            <span>张数</span>
            <input v-model.number="count" type="number" min="1" max="4" />
          </label>
        </div>

        <div class="studio-block">
          <button class="ghost-btn" @click="advanced = !advanced">{{ advanced ? '收起高级项' : '高级项（负向提示词 / 模型）' }}</button>
          <div v-if="advanced" class="adv">
            <label>
              <span>负向提示词（防 AI 味，留空则用内置默认）</span>
              <textarea v-model="negativePrompt" rows="3"></textarea>
            </label>
            <label class="inline-field">
              <span>模型</span>
              <input v-model="model" type="text" />
            </label>
          </div>
        </div>

        <div class="studio-actions">
          <button class="primary-btn" :disabled="generating" @click="generate">
            {{ generating ? '出图中…' : collage ? '生成四宫格 + 4 张单图' : '开始生成' }}
          </button>
          <p v-if="busyText" class="muted small">{{ busyText }}</p>
          <p v-if="error" class="form-error">{{ error }}</p>
        </div>
      </section>

      <section class="studio-result">
        <div v-if="generating" class="result-empty">
          <div class="spinner"></div>
          <p>正在按 {{ (SCENES.find((s) => s.key === scene) || {}).label }} 出图（{{ (SCENES.find((s) => s.key === scene) || {}).ratio }}）…</p>
          <p class="muted small">万相异步任务 + 逐张下载 + 拼图，四宫格一般 30~70 秒。</p>
        </div>
        <div v-else-if="!result" class="result-empty">
          <p class="muted">左边填好，出图结果会落在这里。拼图和每一张单图都能单独下载。</p>
        </div>
        <div v-else>
          <div class="result-meta mono">
            <span>{{ result.ratio }} · {{ result.size }}</span>
            <span>{{ result.count }} 张</span>
            <span>{{ (result.costMs / 1000).toFixed(1) }}s</span>
            <span>{{ result.model }}</span>
            <span v-if="result.collage">含四宫格</span>
            <span v-if="!result.stored">仅远端 URL（24h 过期）</span>
          </div>
          <p v-if="result.collageError" class="form-error">{{ result.collageError }}</p>

          <div v-if="result.collage && result.collage.downloadUrl" class="collage-wrap">
            <div class="strip-head">
              <strong>四宫格拼图</strong>
              <button class="ghost-btn" :disabled="!result.collage.previewUrl" @click="download(result.collage)">下载拼图</button>
            </div>
            <img v-if="result.collage.previewUrl" :src="result.collage.previewUrl" class="big-img" alt="四宫格" />
            <p v-else class="form-error">{{ result.collage.previewError || '拼图取回失败' }}</p>
          </div>

          <div class="strip-head tiles-head">
            <strong>{{ collage ? '组成四宫格的 4 张单图' : '单图' }}</strong>
            <button class="ghost-btn" @click="downloadAll">全部下载</button>
          </div>
          <div class="tiles">
            <figure v-for="img in result.images" :key="img.fileName || img.url" class="tile">
              <img v-if="img.previewUrl" :src="img.previewUrl" :alt="img.fileName" />
              <img v-else-if="img.url" :src="img.url" :alt="img.fileName" />
              <p v-else class="form-error">{{ img.previewError || '取图失败' }}</p>
              <figcaption>
                <span class="mono">{{ img.fileName || '远端图' }}</span>
                <button v-if="img.previewUrl" class="icon-btn" @click="download(img)">下载</button>
              </figcaption>
            </figure>
          </div>

          <details class="prompt-detail">
            <summary>实际喂给模型的提示词</summary>
            <p class="mono small">{{ result.prompt }}</p>
            <p class="muted small">负向：{{ result.negativePrompt }}</p>
          </details>
        </div>
      </section>
    </main>
  </div>
</template>
