<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ArrowUp, BookOpen, Compass, LogOut, MapPin, Plus, RefreshCw, SlidersHorizontal } from '@lucide/vue'
import { errorText, request, setUser } from '../api'
import MarkdownBlock from './MarkdownBlock'
import TripPlan from './TripPlan.vue'
import TravelDialogs from './TravelDialogs.vue'

defineProps({ person: Object })
const trips = ref([])
const tripId = ref('')
const messages = ref([])
const plan = ref(null)
const planTab = ref('output')
const mobileView = ref('chat')
const modelReady = ref(false)
const loading = ref(false)
const sending = ref(false)
const status = ref('')
const failed = ref(false)
const prompt = ref('')
const scroll = ref(null)
const composer = ref(null)
const dialogs = ref(null)
const page = ref(1)
const pages = ref(1)
const moreBusy = ref(false)
let view = 0
const tripTitle = computed(() => plan.value?.requirements?.destination
  || trips.value.find(item => item.id === tripId.value)?.title || '新行程')
const tripDate = computed(() => plan.value?.requirements?.startDate)
const localSuggestions = [
  ['调整交通', '单独调整交通安排，其他部分保持不变。'],
  ['调整住宿', '只调整住宿，请优先考虑靠近地铁的安静住处。'],
  ['调整美食', '只调整美食，想尝目的地的当地特色。']
]

function feedback(text = '', error = false) { status.value = text; failed.value = error }
function fill(text) { prompt.value = text; nextTick(() => composer.value?.focus()) }
async function toBottom() {
  await nextTick()
  if (scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight
}
function clearTrip() {
  view++
  tripId.value = ''
  messages.value = []
  plan.value = null
  planTab.value = 'output'
  mobileView.value = 'chat'
  prompt.value = ''
  page.value = 1
  pages.value = 1
  moreBusy.value = false
  feedback()
}
function newTrip() {
  if (sending.value) return
  clearTrip()
  loading.value = false
  sessionStorage.removeItem('zhixing.trip')
  nextTick(() => composer.value?.focus())
}
async function start() {
  const [runtime, list] = await Promise.allSettled([
    fetch('/api/runtime/status').then(response => {
      if (!response.ok) throw Error('服务暂不可用')
      return response.json()
    }),
    request('/conversation/list')
  ])
  modelReady.value = runtime.status === 'fulfilled' && runtime.value.modelEnabled === true
  if (list.status === 'rejected') { feedback(errorText(list.reason), true); return }
  trips.value = list.value || []
  const selected = sessionStorage.getItem('zhixing.trip')
  if (trips.value.some(item => item.id === selected)) await selectTrip(selected)
}
onMounted(start)

async function selectTrip(id) {
  if (sending.value || id === tripId.value) return
  clearTrip()
  tripId.value = id
  sessionStorage.setItem('zhixing.trip', id)
  const current = view
  loading.value = true
  const results = await Promise.allSettled([readMessages(id, 1), readPlan(id)])
  if (view !== current) return
  loading.value = false
  if (results[0].status === 'fulfilled') {
    applyMessages(results[0].value, false)
    await toBottom()
  }
  if (results[1].status === 'fulfilled') {
    plan.value = results[1].value
    const trip = trips.value.find(row => row.id === id)
    if (trip && plan.value?.requirements?.destination) trip.title = plan.value.requirements.destination
  }
  const error = results.find(result => result.status === 'rejected')
  if (error) feedback(errorText(error.reason), true)
}
function readMessages(id, number) {
  return request('/conversation/messages?' + new URLSearchParams({ conversationId: id, page: number, size: 50 }))
}
function readPlan(id) {
  return request('/conversation/plan?conversationId=' + encodeURIComponent(id))
}
function applyMessages(result, prepend) {
  const entries = (result.records || []).map(item => ({ id: item.id, mine: Number(item.role) === 1, text: item.content }))
  messages.value = prepend ? [...entries, ...messages.value] : entries
  page.value = Number(result.current || 1)
  pages.value = Number(result.pages || 1)
}
async function loadMore() {
  if (moreBusy.value || page.value >= pages.value) return
  const current = view
  moreBusy.value = true
  try {
    const result = await readMessages(tripId.value, page.value + 1)
    if (current !== view) return
    const height = scroll.value?.scrollHeight || 0
    const previous = scroll.value?.scrollTop || 0
    applyMessages(result, true)
    await nextTick()
    if (scroll.value) scroll.value.scrollTop = previous + scroll.value.scrollHeight - height
  } catch (err) { if (view === current) feedback(errorText(err), true) }
  finally { if (view === current) moreBusy.value = false }
}

async function send(forced) {
  const text = (forced ?? prompt.value).trim()
  if (!text || sending.value || loading.value || !modelReady.value) return
  const current = view
  sending.value = true
  feedback()
  try {
    if (!tripId.value) {
      const trip = await request('/conversation/create', { method: 'POST' })
      if (current !== view) return
      tripId.value = trip.id
      trips.value.unshift(trip)
      sessionStorage.setItem('zhixing.trip', trip.id)
    }
    messages.value.push({ id: crypto.randomUUID(), mine: true, text })
    if (forced == null) prompt.value = ''
    toBottom()
    const answer = await request('/travel/chat', {
      method: 'POST', body: { sessionId: tripId.value, prompt: text }, plain: true
    })
    if (current !== view) return
    messages.value.push({ id: crypto.randomUUID(), mine: false, text: answer })
    toBottom()
    try {
      const updated = await readPlan(tripId.value)
      if (current !== view) return
      const planned = /【(?:行程规划汇总|交通方案更新|住宿方案更新|美食方案更新)】/.test(answer)
      if (updated && (planned || updated.output !== plan.value?.output)) planTab.value = 'output'
      plan.value = updated
      const trip = trips.value.find(row => row.id === tripId.value)
      if (trip && updated?.requirements?.destination) trip.title = updated.requirements.destination
    } catch { if (current === view) feedback('回复已保存，重新打开此行程可刷新方案。', true) }
  } catch (err) {
    if (current === view) { if (forced == null && !prompt.value) prompt.value = text; feedback(errorText(err), true) }
  } finally { if (current === view) sending.value = false }
}
function enter(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  send()
}
async function logout() {
  try { await request('/user/logout', { method: 'POST' }) } catch { /* Close local session as well. */ }
  finally { setUser(null) }
}
</script>

<template>
  <div class="travel-shell">
    <aside class="trip-sidebar" aria-label="行程导航">
      <header class="wordmark"><span class="brand-icon"><Compass :size="19" /></span> 知行旅行</header>
      <button class="primary new-trip" :disabled="sending" @click="newTrip"><Plus :size="17" /> 新行程</button>
      <h2 class="sidebar-label">我的行程</h2>
      <nav class="trip-list" aria-label="我的行程">
        <button v-for="item in trips" :key="item.id" :aria-current="item.id === tripId" :disabled="sending" @click="selectTrip(item.id)">
          <MapPin :size="16" /> <span>{{ item.title || '新行程' }}</span>
        </button>
        <p v-if="!trips.length" class="muted">暂无行程</p>
      </nav>
      <div class="side-tools">
        <button @click="dialogs.open('library')"><BookOpen :size="17" /> 旅行资料</button>
        <button @click="dialogs.open('preferences')"><SlidersHorizontal :size="17" /> 旅行偏好</button>
      </div>
      <footer class="profile">
        <span class="profile-avatar">{{ (person.username || person.email || '知').slice(0, 1).toUpperCase() }}</span>
        <span>{{ person.username || person.email }}</span>
        <button class="icon-button" aria-label="退出登录" title="退出登录" @click="logout"><LogOut :size="17" /></button>
      </footer>
    </aside>
    <main class="travel-main">
      <header class="trip-heading">
        <div><h1>{{ tripTitle }}</h1><span v-if="tripDate" class="trip-date">{{ tripDate }}</span></div>
        <button v-if="plan" class="outline-button" :disabled="sending || !modelReady" @click="send('重新查询实时信息')"><RefreshCw :size="15" /> 重新查询</button>
      </header>
      <nav class="mobile-tabs" aria-label="行程页面">
        <button :aria-pressed="mobileView === 'chat'" @click="mobileView = 'chat'">规划对话</button>
        <button :aria-pressed="mobileView === 'plan'" @click="mobileView = 'plan'">行程方案</button>
      </nav>
      <div class="travel-workarea" :data-mobile-view="mobileView">
        <section class="chat-panel" aria-label="规划对话">
          <header class="panel-heading">
            <h2>规划对话</h2>
            <button v-if="page < pages" class="text-button" :disabled="moreBusy" @click="loadMore">更早的消息</button>
          </header>
          <div ref="scroll" class="chat-scroll" role="log" aria-label="对话记录">
            <p v-if="loading" class="chat-empty muted">正在读取行程…</p>
            <div v-else-if="!messages.length" class="chat-empty">
              <span class="brand-icon"><Compass :size="21" /></span>
              <h3>下一站想去哪里？</h3>
              <p>告诉知行你想去哪儿，时间和人数可以在对话里补充。</p>
            </div>
            <article v-for="message in messages" :key="message.id" class="message" :class="{ mine: message.mine }">
              <header>{{ message.mine ? '你' : '知行' }}</header>
              <div v-if="message.mine" class="message-text">{{ message.text }}</div>
              <MarkdownBlock v-else :text="message.text" />
            </article>
            <p v-if="sending" class="generation-status" role="status">正在查询并整理行程…</p>
          </div>
          <div class="travel-composer">
            <div v-if="plan" class="quick-actions">
              <button v-for="[label, text] in localSuggestions" :key="label" @click="fill(text)">{{ label }}</button>
            </div>
            <div v-else-if="!messages.length" class="quick-actions">
              <button @click="fill('从_____出发，想去_____过周末，同行_____位。')">周末短途</button>
            </div>
            <form @submit.prevent="send()">
              <label class="sr-only" for="prompt">旅行需求</label>
              <textarea id="prompt" ref="composer" v-model="prompt" maxlength="8000" rows="2" required placeholder="输入旅行需求" @keydown="enter"></textarea>
              <button class="primary send-button" aria-label="发送" title="发送" :disabled="sending || loading || !modelReady || !prompt.trim()"><ArrowUp :size="20" /></button>
            </form>
            <p v-if="status" class="feedback" :class="{ error: failed }" role="status">{{ status }}</p>
            <p v-else-if="!modelReady" class="feedback muted">规划模型暂不可用，仍可浏览已保存的行程。</p>
          </div>
        </section>
        <TripPlan v-model="planTab" :plan="plan" :trip-title="tripTitle" />
      </div>
    </main>
    <TravelDialogs ref="dialogs" />
  </div>
</template>

