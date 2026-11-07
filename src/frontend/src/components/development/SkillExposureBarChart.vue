<template>
  <div class="skill-exposure-bar-chart">
    <v-chart :option="chartOption" autoresize style="height: 320px" />
    <!-- The chart is a canvas: its neglected-skill labels are invisible to assistive tech (and to
         tests). This list carries the same alerts as text. Found by Pitchside DEV-04. -->
    <ul v-if="neglectedCodes.length" class="sr-only" data-testid="exposure-neglected">
      <li v-for="code in neglectedCodes" :key="code" data-testid="exposure-neglected-skill">
        {{ $t('development.neglectedSkillAlert', { skill: code }) }}
      </li>
    </ul>
    <div v-if="hasZeroSkills" class="zero-skills-row">
      <!-- A neglected skill with no exposure at all this week is the most neglected of all: it
           never reaches the bar chart, so its chip carries the alert instead of a neutral grey. -->
      <q-chip
        v-for="skill in zeroExposureSkills"
        :key="skill"
        :color="neglectedCodes.includes(skill) ? 'warning' : 'grey-3'"
        :text-color="neglectedCodes.includes(skill) ? 'white' : 'grey-7'"
        size="sm"
        data-testid="exposure-zero-skill"
        :data-skill="skill"
        :data-neglected="neglectedCodes.includes(skill)"
      >
        {{ skill }} —
        {{
          neglectedCodes.includes(skill)
            ? $t('development.neglectedSkillAlert', { skill })
            : $t('development.noExposureYet')
        }}
      </q-chip>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { use } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'

use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

const { t } = useI18n()

const props = defineProps({
  currentWeek: { type: Object, default: () => ({}) },
  neglectedCodes: { type: Array, default: () => [] },
  skillDefinitions: { type: Array, default: () => [] },
})

const sortedEntries = computed(() => {
  return Object.entries(props.currentWeek ?? {}).sort((a, b) => b[1] - a[1])
})

const zeroExposureSkills = computed(() => {
  const exposed = new Set(Object.keys(props.currentWeek ?? {}))
  return props.skillDefinitions.map((s) => s.code).filter((c) => !exposed.has(c))
})

const hasZeroSkills = computed(() => zeroExposureSkills.value.length > 0)

const chartOption = computed(() => {
  const codes = sortedEntries.value.map(([code]) => code)
  const values = sortedEntries.value.map(([, slu]) => slu)
  const colors = codes.map((code) => (props.neglectedCodes.includes(code) ? '#f59e0b' : '#3b82f6'))

  return {
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: codes,
      axisLabel: { interval: 0, rotate: codes.length > 8 ? 30 : 0 },
    },
    yAxis: { type: 'value', name: 'SLU' },
    series: [
      {
        type: 'bar',
        data: values.map((v, i) => {
          const isNeglected = props.neglectedCodes.includes(codes[i])
          return {
            value: v,
            itemStyle: { color: colors[i] },
            label: {
              show: true,
              position: 'top',
              formatter: (params) =>
                isNeglected
                  ? `${params.value}\n${t('development.neglectedSkillAlert', { skill: codes[i] })}`
                  : String(params.value),
            },
          }
        }),
      },
    ],
  }
})
</script>
