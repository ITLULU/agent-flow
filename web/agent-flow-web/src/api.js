const TOKEN_KEY = 'af_token'

export class ApiError extends Error {
  constructor(message, code) {
    super(message)
    this.code = code || ''
  }
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

/** 统一 API 调用：401 立即清 token（由 App 引导回登录），业务错误抛出 code+message。 */
export async function api(path, { method = 'GET', body } = {}) {
  const headers = {}
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  let res
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  } catch (e) {
    throw new ApiError(`无法连接后端：${e.message}`)
  }
  let payload = null
  try {
    payload = await res.json()
  } catch {
    throw new ApiError(`后端返回非 JSON（HTTP ${res.status}）`)
  }
  if (res.status === 401) {
    setToken('')
    window.dispatchEvent(new CustomEvent('af:unauthorized'))
    throw new ApiError(payload?.message || '登录已过期，请重新登录', payload?.code || 'A0401')
  }
  if (!payload || payload.code !== '0') {
    throw new ApiError(payload?.message || `请求失败（HTTP ${res.status}）`, payload?.code)
  }
  return payload.data
}

export async function login(username, password) {
  return api('/api/auth/login', { method: 'POST', body: { username, password } })
}

export async function register(username, password) {
  return api('/api/auth/register', { method: 'POST', body: { username, password } })
}

/** 成品文件下载需要带 Authorization，普通 <a href> 带不上，这里转成 blob URL。 */
export async function fetchBlob(path) {
  const res = await fetch(path, { headers: { Authorization: `Bearer ${getToken()}` } })
  if (!res.ok) throw new ApiError(`下载失败（HTTP ${res.status}）`)
  return res.blob()
}
