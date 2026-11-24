<script setup>
import { computed, ref } from 'vue'
import { ArrowRight, Compass } from '@lucide/vue'
import { errorText, isLocal, request, setUser } from '../api'

const register = ref(false)
const account = ref('')
const password = ref('')
const nickname = ref('')
const busy = ref(false)
const error = ref('')
const supportsTrial = computed(() => isLocal && !register.value)

async function submit(trial = false) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const name = trial ? '123456' : account.value.trim()
    const email = supportsTrial.value && name === '123456' ? 'demo-a@example.com' : name
    const body = { email, password: trial ? '123456' : password.value }
    if (register.value) {
      await request('/user/register', { method: 'POST', body: { ...body, username: nickname.value.trim() } })
    }
    setUser(await request('/user/login', { method: 'POST', body }))
  } catch (err) { error.value = errorText(err) }
  finally { busy.value = false }
}
function toggle() {
  register.value = !register.value
  error.value = ''
}
</script>

<template>
  <main class="travel-login">
    <section class="login-side">
      <header class="wordmark"><span class="brand-icon"><Compass :size="19" /></span> 知行旅行</header>
      <div class="login-content">
        <h1>{{ register ? '创建你的旅行账户' : '登录知行' }}</h1>
        <form class="field-stack" @submit.prevent="submit()">
          <label v-if="register">昵称<input v-model="nickname" autocomplete="nickname" maxlength="64" required></label>
          <label>{{ supportsTrial ? '邮箱或试用账号' : '电子邮箱' }}
            <input v-model="account" :type="supportsTrial ? 'text' : 'email'" inputmode="email" autocomplete="username" required>
          </label>
          <label>密码
            <input v-model="password" type="password" :autocomplete="register ? 'new-password' : 'current-password'" required>
          </label>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <button class="primary login-submit" :disabled="busy">{{ register ? '创建账号' : '登录' }} <ArrowRight :size="17" /></button>
        </form>
        <div class="login-links">
          <button type="button" class="text-button" @click="toggle">{{ register ? '返回登录' : '创建账号' }}</button>
          <button v-if="supportsTrial" type="button" class="text-button" :disabled="busy" @click="submit(true)">使用本机试用账号</button>
        </div>
      </div>
    </section>
    <figure class="login-photo"><figcaption>杭州 · 西湖</figcaption></figure>
  </main>
</template>

