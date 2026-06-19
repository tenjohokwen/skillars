<template>
  <v-chart :option="chartOption" autoresize style="height: 320px" />
</template>

<script setup>
import { computed } from 'vue'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'

use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps({
  trend: { type: Array, default: () => [] },
  skillDefinitions: { type: Array, default: () => [] },
})

const weekLabels = computed(() =>
  props.trend.map((w) => `${w.isoYear}-W${String(w.isoWeek).padStart(2, '0')}`),
)

const allSkillCodes = computed(() => {
  const codes = new Set()
  for (const week of props.trend) {
    Object.keys(week.sluPerSkill ?? {}).forEach((c) => codes.add(c))
  }
  return [...codes]
})

const series = computed(() =>
  allSkillCodes.value.map((code) => ({
    name: code,
    type: 'line',
    data: props.trend.map((w) => w.sluPerSkill?.[code] ?? 0),
    smooth: true,
  })),
)

const chartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: {
    data: allSkillCodes.value,
    selectedMode: 'multiple',
    type: 'scroll',
  },
  xAxis: { type: 'category', data: weekLabels.value },
  yAxis: { type: 'value', name: 'SLU' },
  series: series.value,
}))
</script>
