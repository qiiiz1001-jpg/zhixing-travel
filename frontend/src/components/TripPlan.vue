<script setup>
import { computed } from 'vue'
import { CalendarDays, MapPin, UsersRound } from '@lucide/vue'
import PlanSection from './PlanSection.vue'

const props = defineProps({ plan: Object, tripTitle: String })
const tab = defineModel({ default: 'output' })
const sections = [
  { key: 'transportOutput', label: '交通' },
  { key: 'accommodationOutput', label: '住宿' },
  { key: 'foodOutput', label: '美食' }
]
const tabs = [{key:'output', label:'总览'}, ...sections]
const show = computed(() => tab.value === 'output' ? sections : sections.filter(section => section.key === tab.value))
const needs = computed(() => props.plan?.effectiveRequirements || props.plan?.requirements || {})
const preferences = computed(() => [
  needs.value.special, needs.value.transportPreferences,
  needs.value.accommodationPreferences, needs.value.foodPreferences
].filter(value => value && value !== '无'))
const facts = computed(() => [needs.value.origin, needs.value.destination, needs.value.startDate, needs.value.people])
</script>

<template>
  <section class="plan-panel" aria-label="最新行程方案">
    <header class="panel-heading">
      <div><h2>行程方案</h2><span v-if="plan" class="muted">{{ tripTitle }}</span></div>
      <nav v-if="plan" class="plan-tabs" aria-label="行程方案分类">
        <button v-for="item in tabs" :key="item.key" :aria-pressed="tab === item.key" @click="tab = item.key">{{ item.label }}</button>
      </nav>
    </header>
    <div v-if="!plan" class="plan-empty">
      <figure><figcaption>杭州 · 西湖</figcaption></figure>
      <div><h3>行程待生成</h3><p>在对话里补充出发地、目的地、日期和人数后，交通、住宿与美食会汇总到这里。</p></div>
    </div>
    <div v-else :key="tab" class="plan-scroll">
      <div v-if="tab === 'output'" class="plan-facts">
        <span v-if="facts[0] || facts[1]"><MapPin :size="15" /> {{ [facts[0], facts[1]].filter(Boolean).join(' → ') }}</span>
        <span v-if="facts[2]"><CalendarDays :size="15" /> {{ facts[2] }}</span>
        <span v-if="facts[3]"><UsersRound :size="15" /> {{ /^\d+$/.test(facts[3]) ? facts[3] + ' 人' : facts[3] }}</span>
      </div>
      <details v-if="tab==='output' && preferences.length" class="trip-preferences">
        <summary>本次要求</summary><p>{{ preferences.join(' · ') }}</p>
      </details>
      <PlanSection v-for="item in show" :key="item.key" :category="item.key" :content="plan[item.key]"
        :preview="tab==='output'" @expand="tab=item.key" />
    </div>
  </section>
</template>

