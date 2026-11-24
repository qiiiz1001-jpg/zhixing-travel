<script setup>
import { computed } from 'vue'
import { BedDouble, Car, MapPin, Plane, Route, TrainFront, Utensils } from '@lucide/vue'
import MarkdownBlock from './MarkdownBlock'

const props=defineProps({ category:String, content:String, preview:Boolean })
const emit=defineEmits(['expand'])
const modeIcons={ 自驾:Car, 高铁:TrainFront, 航班:Plane }
const icons={ transportOutput:Route, accommodationOutput:BedDouble, foodOutput:Utensils }
const labels={ transportOutput:'交通', accommodationOutput:'住宿', foodOutput:'美食' }
const modePattern=/^【(自驾|高铁|航班)】\s*$/gm
const content=computed(()=>props.content||'')
function rows(text) {
  return text.split(/\r?\n/).filter(line=>line.startsWith('- ')).map(line=>line.slice(2).split(' · '))
}
const modes=computed(()=>{
  if(props.category!=='transportOutput')return []
  const text=content.value
  const offsets=[...text.matchAll(modePattern)]
  return offsets.map((match,index)=>{
    const body=text.slice(match.index+match[0].length,offsets[index+1]?.index??text.length).trim()
    const label=match[1]
    const tickets=rows(body).filter(parts=>parts.length>=4 && /^[\w-]+\d[\w-]*$/.test(parts[0]) && parts[1].includes(' → ')).map(ticketFields)
    const drive=label==='自驾'&&body.includes('总耗时')
    const duration=drive ? body.match(/总耗时[：:]\s*([^\n]+)/)?.[1] : ''
    const length=drive ? body.match(/预计里程[：:]\s*([^\n]+)/)?.[1] : ''
    const notice=body.split('\n').find(line=>line.includes('接驳用时'))||''
    return {label,body,tickets,drive,duration,length,notice}
  })
})
const placeRows=computed(()=>{
  if(props.category==='transportOutput')return []
  return rows(content.value).filter(parts=>parts.length>=2).map(placeFields)
})
const placeIntro=computed(()=>content.value.split(/\r?\n/).filter(line=>line.trim()&&!line.startsWith('- ')).join('\n').trim())
function shown(list) {
  if (!props.preview) return list
  return props.category === 'foodOutput' ? list.slice(-2) : list.slice(0,2)
}
function ticketFields(row) {
  const stops=row[1].split(' → ')
  const times=row[2].split(' → ')
  const format=time=>time?.match(/\b\d{2}:\d{2}\b/)?.[0]||time||'—'
  const leaveDate=times[0]?.match(/^\d{4}-\d{2}-\d{2}/)?.[0]
  const arriveDate=times[1]?.match(/^\d{4}-\d{2}-\d{2}/)?.[0]
  return { number:row[0], from:stops[0], to:stops[1], leave:format(times[0]), arrive:format(times[1]),
    arrivesLater:arriveDate && leaveDate && leaveDate!==arriveDate ? arriveDate : '', fullTime:row[2], facts:row.slice(3).join(' · ') }
}
function placeFields(row) {
  const price=row.find(field=>field.startsWith('起价 ¥'))
  const evidence=row.includes('高德特色菜标签')
  if (props.category==='accommodationOutput') return {name:row[0], address:row[1], price}
  return row.length>=3 ? {name:row[1],address:row[2],dish:row[0],evidence} : {name:row[0],address:row[1]}
}
</script>

<template>
  <article class="plan-section">
    <header><component :is="icons[category]" :size="21" /><h3>{{ labels[category] }}</h3>
      <button v-if="preview && (modes.length || placeRows.length>2)" class="text-button plan-expand" @click="emit('expand')">查看全部{{ placeRows.length ? ' '+placeRows.length : '' }}</button>
    </header>
    <template v-if="modes.length">
      <section v-for="mode in modes" :key="mode.label" class="route-mode">
        <h4><component :is="modeIcons[mode.label]" :size="17"/> {{ mode.label }}</h4>
        <template v-if="mode.tickets.length">
          <article v-for="(row,index) in shown(mode.tickets)" :key="index" class="ticket-row">
            <div class="ticket-main"><strong>{{ row.number }}</strong>
              <div :title="row.fullTime"><time>{{ row.leave }}</time><span>{{ row.from }}</span></div>
              <span class="ticket-line" aria-hidden="true"></span>
              <div :title="row.fullTime"><time>{{ row.arrive }}</time><span>{{ row.to }} {{ row.arrivesLater }}</span></div>
            </div>
            <p>{{ row.facts }}</p>
          </article>
          <p v-if="mode.notice" class="muted route-note">{{ mode.notice }}</p>
        </template>
        <template v-else-if="mode.drive && preview">
          <p class="route-facts">{{ [mode.duration,mode.length].filter(Boolean).join(' · ') }}</p>
        </template>
        <MarkdownBlock v-else :text="mode.body"/>
      </section>
    </template>
    <template v-else-if="placeRows.length">
      <MarkdownBlock class="place-intro" v-if="placeIntro" :text="placeIntro"/>
      <article v-for="(row,index) in shown(placeRows)" :key="index" class="place-row">
        <div>
          <span v-if="row.dish" class="dish-label">{{ row.dish }}</span>
          <strong>{{ row.name }}</strong>
          <p><MapPin :size="13"/> {{ row.address }}</p>
          <small v-if="row.evidence" class="muted">高德特色菜标签</small>
        </div>
        <span v-if="row.price" class="hotel-price">{{ row.price }}</span>
      </article>
    </template>
    <MarkdownBlock v-else-if="content" :text="content"/>
    <p v-else class="muted">暂无结果，可在对话里补充要求。</p>
  </article>
</template>

