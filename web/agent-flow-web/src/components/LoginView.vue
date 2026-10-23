<script setup>
import { ref } from 'vue'
import { login, register, ApiError } from '../api'

const emit = defineEmits(['authenticated'])
const mode = ref('login')
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const notice = ref('')

const submit = async () => {
  error.value = ''
  notice.value = ''
  if (!username.value || !password.value) {
    error.value = '请填写用户名与口令'
    return
  }
  busy.value = true
  try {
    if (mode.value === 'register') {
      await register(username.value, password.value)
      notice.value = '注册成功，正在登录…'
    }
    const session = await login(username.value, password.value)
    emit('authenticated', session)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-art">
      <div class="login-brand">agent-flow</div>
      <h1>把内容生产<br />画成一条流水线</h1>
      <p>热点采集 → 文案成稿 → 封面主图 → 成品归档 / 渠道草稿。拖节点、连线、运行，执行日志与降级状态一目了然。</p>
      <ul>
        <li>可视化 DAG 编辑，保存即校验</li>
        <li>节点级超时 / 重试 / 降级可追溯</li>
        <li>成品库与草稿通道同一处收口</li>
      </ul>
    </div>
    <div class="login-panel">
      <div class="login-card">
        <div class="tabs">
          <button :class="{ active: mode === 'login' }" @click="mode = 'login'; error = ''; notice = ''">登录</button>
          <button :class="{ active: mode === 'register' }" @click="mode = 'register'; error = ''; notice = ''">注册</button>
        </div>
        <form @submit.prevent="submit">
          <label>
            <span>用户名</span>
            <input v-model.trim="username" autocomplete="username" placeholder="3-32 位字母/数字/下划线" />
          </label>
          <label>
            <span>口令</span>
            <input v-model="password" type="password" autocomplete="current-password" placeholder="8-64 位" />
          </label>
          <p v-if="error" class="form-error">{{ error }}</p>
          <p v-if="notice" class="form-notice">{{ notice }}</p>
          <button class="primary-btn wide" type="submit" :disabled="busy">
            {{ busy ? '请稍候…' : mode === 'login' ? '进入工作台' : '注册并登录' }}
          </button>
        </form>
        <p class="login-hint">注册账号默认是普通用户；管理员由部署侧 bootstrap 配置注入。</p>
      </div>
    </div>
  </div>
</template>
