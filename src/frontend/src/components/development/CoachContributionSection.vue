<template>
  <div v-if="topAttributions.length > 0">
    <div
      v-for="item in topAttributions"
      :key="`${item.skillCode}::${item.coachDisplayName}`"
      class="text-body2 q-mb-xs"
      style="color: var(--text-secondary)"
    >
      {{ $t('development.portal.coachContribution', {
          coachName: firstNameOf(item.coachDisplayName),
          pct: item.percentageContribution,
          skill: item.skillCode
       }) }}
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ contributions: { type: Array, default: () => [] } })

const topAttributions = computed(() => {
  const bySkill = {}
  for (const c of props.contributions) {
    if (!bySkill[c.skillCode] || c.percentageContribution > bySkill[c.skillCode].percentageContribution) {
      bySkill[c.skillCode] = c
    }
  }
  return Object.values(bySkill).filter(c => c.percentageContribution >= 30)
})

function firstNameOf(displayName) {
  return displayName?.split(' ')?.[0] ?? displayName
}
</script>
