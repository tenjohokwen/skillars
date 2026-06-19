<template>
  <div>
    <div v-if="!radarEntries || radarEntries.entries.length === 0" class="text-caption text-grey q-pa-md">
      {{ $t('development.radar.noEntriesYet') }}
    </div>

    <q-list v-else separator>
      <q-item v-for="(entry, idx) in radarEntries.entries" :key="idx">
        <q-item-section>
          <q-item-label>
            {{ entry.skillCode }}
            <q-badge :color="typeColor(entry.assessmentType)" class="q-ml-xs">
              {{ entry.assessmentType }}
            </q-badge>
          </q-item-label>
          <q-item-label caption>
            {{ entry.assessmentDate }} — {{ $t('development.radar.scoreLabel') }}: {{ entry.score }}
          </q-item-label>
          <q-item-label v-if="entry.notes" caption class="text-grey-7">
            {{ entry.notes }}
          </q-item-label>
          <q-item-label
            v-if="otherCoachCount(entry.skillCode) >= 1 && firstIndexBySkillCode[entry.skillCode] === idx"
            caption
            class="text-info"
          >
            {{ $t('development.radar.otherCoachCount', {
              count: otherCoachCount(entry.skillCode),
              skill: entry.skillCode
            }) }}
          </q-item-label>
        </q-item-section>
      </q-item>
    </q-list>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  radarEntries: { type: Object, default: null },
})

function otherCoachCount(skillCode) {
  return props.radarEntries?.otherCoachCounts?.[skillCode] ?? 0
}

const firstIndexBySkillCode = computed(() => {
  const map = {}
  ;(props.radarEntries?.entries ?? []).forEach((e, i) => {
    if (!(e.skillCode in map)) map[e.skillCode] = i
  })
  return map
})

function typeColor(type) {
  if (type === 'OBJECTIVE') return 'primary'
  if (type === 'MATCH_OBSERVATION') return 'secondary'
  return 'accent'
}
</script>
