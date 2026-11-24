<script setup>
import { reactive, ref } from 'vue'
import { FileText, X } from '@lucide/vue'
import { errorText, request } from '../api'

const modal = ref(null)
const view = ref('')
const status = ref('')
const failed = ref(false)
const busy = ref(false)
const selectFile = ref(null)
const documents = ref([])
const formats = ref(null)
const query = ref('')
const results = ref([])
const categories = { transport:'交通', accommodation:'住宿', food:'美食' }
const fields = reactive({ transport:'', accommodation:'', food:'' })
let version = -1

function feedback(text='', error=false) { status.value=text; failed.value=error }
async function run(task) {
  if (busy.value) return
  busy.value = true
  feedback()
  try { await task() }
  catch (error) { feedback(errorText(error), true) }
  finally { busy.value = false }
}
async function readLibrary() { documents.value = await request('/knowledge/documents') || [] }
function applyProfile(profile) {
  version = profile.version
  for (const key of Object.keys(categories)) fields[key] = profile.preferences[key] || ''
}
function open(next) {
  view.value = next
  results.value = []
  query.value = ''
  if (!modal.value.open) modal.value.showModal()
  return run(async () => {
    if (next === 'library') {
      const [configured] = await Promise.all([request('/knowledge/formats'), readLibrary()])
      formats.value = configured
    } else applyProfile(await request('/memory/preferences'))
  })
}
defineExpose({ open })
const labels = { READY:'可用', FAILED:'索引失败', PENDING:'等待索引', INDEXING:'正在索引' }

const upload = () => run(async () => {
  const file = selectFile.value.files[0]
  if (!file) throw Error('请选择文件。')
  if (file.size > (formats.value?.maxBytes || 20971520)) throw Error('文件超过大小限制。')
  const body = new FormData()
  body.append('file', file)
  await request('/knowledge/documents', { method:'POST', body })
  selectFile.value.value = ''
  await readLibrary()
  feedback('资料已上传。')
})
const search = () => run(async () => {
  results.value = await request('/knowledge/search', { method:'POST', body:{query:query.value.trim()} }) || []
  if (!results.value.length) feedback('未找到匹配的资料。')
})
function remove(item) {
  if (!confirm('删除「' + item.filename + '」？')) return
  run(async () => {
    await request('/knowledge/documents/' + encodeURIComponent(item.id), { method:'DELETE' })
    results.value = []
    await readLibrary()
    feedback('资料已删除。')
  })
}
function save(clear = false) {
  if (clear && !confirm('清除所有长期旅行偏好？')) return
  return run(async () => {
    const preferences = Object.fromEntries(Object.keys(categories).map(key => [key, clear ? '' : fields[key].trim()]))
    try {
      applyProfile(await request('/memory/preferences', {
        method:'PUT', body:{ expectedVersion:version, operationId:crypto.randomUUID(), preferences }
      }))
      feedback('已保存，下次规划时生效。')
    } catch (error) {
      const profile = await request('/memory/preferences').catch(() => null)
      if (profile) version = profile.version
      throw error
    }
  })
}
</script>

<template>
  <dialog ref="modal" class="travel-dialog" aria-labelledby="travel-dialog-title">
    <header class="panel-heading">
      <h2 id="travel-dialog-title">{{ view === 'library' ? '旅行资料' : '旅行偏好' }}</h2>
      <button class="icon-button" aria-label="关闭" @click="modal.close()"><X :size="20"/></button>
    </header>
    <div class="dialog-body">
      <template v-if="view === 'library'">
        <form class="inline-form" @submit.prevent="upload">
          <input ref="selectFile" type="file" aria-label="选择资料" :accept="formats?.extensions.map(ext => '.' + ext).join(',')" required>
          <button class="primary" :disabled="busy">上传资料</button>
        </form>
        <p v-if="formats" class="muted">每份最多 {{ Math.floor(formats.maxBytes / 1048576) }} MB</p>
        <p v-if="!documents.length" class="muted">暂无旅行资料</p>
        <div v-for="item in documents" :key="item.id" class="doc-row">
          <FileText :size="19" />
          <div><strong>{{ item.filename }}</strong><span>{{ labels[item.status] || item.status }} · {{ item.chunkCount }} 个片段</span></div>
          <button class="text-button" :disabled="busy" @click="remove(item)">删除</button>
        </div>
        <form class="inline-form" @submit.prevent="search">
          <input v-model="query" maxlength="2000" placeholder="搜索旅行资料" aria-label="搜索旅行资料" required>
          <button class="outline-button" :disabled="busy">搜索</button>
        </form>
        <p v-for="(item, index) in results" :key="index" class="search-result">{{ item }}</p>
      </template>
      <form v-else class="field-stack" @submit.prevent="save()">
        <p class="muted">本次对话提出的要求优先于长期偏好。</p>
        <label v-for="(label, key) in categories" :key="key">{{ label }}
          <textarea v-model="fields[key]" maxlength="300" rows="2"></textarea>
        </label>
        <div class="inline-form">
          <button class="primary" :disabled="busy || version < 0">保存偏好</button>
          <button type="button" class="outline-button" :disabled="busy || version < 0" @click="save(true)">清除全部</button>
        </div>
      </form>
      <p v-if="busy" class="muted" role="status">正在处理…</p>
      <p v-else-if="status" :class="{ error: failed }" role="status">{{ status }}</p>
    </div>
  </dialog>
</template>

