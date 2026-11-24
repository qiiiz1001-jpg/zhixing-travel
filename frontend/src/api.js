import { shallowRef } from 'vue'

const storageKey = 'zhixing.user'
function savedUser() {
  try { return JSON.parse(sessionStorage.getItem(storageKey)) }
  catch { return null }
}
export const user = shallowRef(savedUser())
export const isLocal = ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)
export function setUser(value) {
  user.value = value
  if (value) sessionStorage.setItem(storageKey, JSON.stringify(value))
  else {
    sessionStorage.removeItem(storageKey)
    sessionStorage.removeItem('zhixing.trip')
  }
}
export async function request(path, { method = 'GET', body, plain = false } = {}) {
  const identity = user.value
  const headers = identity?.token ? { Authorization: 'Bearer ' + identity.token } : {}
  const form = body instanceof FormData
  if (body != null && !form) headers['Content-Type'] = 'application/json'
  const response = await fetch('/api' + path, {
    method, headers, body: body == null || form ? body : JSON.stringify(body)
  })
  const text = await response.text()
  let result
  try { result = JSON.parse(text) } catch { result = null }
  if (response.status === 401 && identity && identity === user.value) setUser(null)
  if (identity && identity !== user.value) throw Error('登录已过期，请重新登录。')
  if (!response.ok || result?.success === false) throw Error(result?.errMsg || '请求失败，请稍后重试。')
  if (plain) return text
  if (!result || result.success !== true) throw Error('服务返回了无法读取的结果。')
  return result.data
}
export function errorText(error) {
  return error.message === 'Failed to fetch' ? '暂时连接不上服务，请检查网络后重试。' : error.message
}

