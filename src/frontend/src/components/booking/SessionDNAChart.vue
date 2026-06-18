<template>
  <div class="session-dna-chart" :class="`session-dna-chart--${props.variant}`">
    <svg
      :width="svgSize"
      :height="svgSize"
      :viewBox="`0 0 ${svgSize} ${svgSize}`"
      xmlns="http://www.w3.org/2000/svg"
      class="session-dna-chart__radar"
      :aria-label="t('session.dna.chartAriaLabel')"
    >
      <!-- Grid rings -->
      <polygon
        v-for="ring in rings"
        :key="ring"
        :points="polygonPoints(ring / rings.length)"
        fill="none"
        stroke="var(--border-subtle, rgba(255,255,255,0.15))"
        stroke-width="1"
      />

      <!-- Axis lines -->
      <line
        v-for="(axis, i) in axes"
        :key="axis.key"
        :x1="cx"
        :y1="cy"
        :x2="outerPoint(i).x"
        :y2="outerPoint(i).y"
        stroke="var(--border-subtle, rgba(255,255,255,0.15))"
        stroke-width="1"
      />

      <!-- Data polygon -->
      <polygon
        :points="dataPoints"
        fill="var(--accent-primary, #8b5cf6)"
        fill-opacity="0.25"
        stroke="var(--accent-primary, #8b5cf6)"
        stroke-width="2"
        stroke-linejoin="round"
      />

      <!-- Data dots -->
      <circle
        v-for="(p, i) in dataDots"
        :key="i"
        :cx="p.x"
        :cy="p.y"
        r="4"
        fill="var(--accent-primary, #8b5cf6)"
      />

      <!-- Axis labels (full variant only) -->
      <template v-if="props.variant === 'full'">
        <text
          v-for="(axis, i) in axes"
          :key="`lbl-${axis.key}`"
          :x="labelPoint(i).x"
          :y="labelPoint(i).y"
          text-anchor="middle"
          dominant-baseline="middle"
          font-size="10"
          fill="var(--text-secondary, rgba(255,255,255,0.7))"
        >
          {{ t(`session.dna.axis.${axis.key}`) }}
        </text>
      </template>
    </svg>

    <!-- Compact: small legend row -->
    <div v-if="props.variant === 'compact'" class="session-dna-chart__legend row q-gutter-sm justify-center">
      <div v-for="axis in axes" :key="axis.key" class="col-auto text-center">
        <div class="text-caption text-secondary">{{ t(`session.dna.axis.${axis.key}`) }}</div>
        <div class="text-body2 text-bold">{{ props.sessionDna[axis.key] ?? 0 }}</div>
      </div>
    </div>

    <!-- Confirmation tick (shown in WrapUp) -->
    <div v-if="props.showConfirmation" class="session-dna-chart__confirmed row items-center q-mt-sm">
      <q-icon name="check_circle" color="positive" size="24px" />
      <span class="text-body2 q-ml-xs text-positive">{{ t('booking.completion.summaryTitle') }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

defineOptions({ name: 'SessionDNAChart' })

const props = defineProps({
  sessionDna: {
    type: Object,
    default: () => ({ technical: 0, physical: 0, cognitive: 0, matchRealism: 0, weakFootFocus: 0 }),
  },
  variant: { type: String, default: 'compact' },
  showConfirmation: { type: Boolean, default: false },
  bookingId: { type: String, default: null },
})

const { t } = useI18n()

const axes = [
  { key: 'technical' },
  { key: 'physical' },
  { key: 'cognitive' },
  { key: 'matchRealism' },
  { key: 'weakFootFocus' },
]
const rings = [1, 2, 3, 4]

const svgSize = computed(() => props.variant === 'full' ? 240 : 160)
const cx = computed(() => svgSize.value / 2)
const cy = computed(() => svgSize.value / 2)
const radius = computed(() => svgSize.value * 0.38)
const labelOffset = computed(() => svgSize.value * 0.08)

function angleFor(i) {
  return (i / axes.length) * 2 * Math.PI - Math.PI / 2
}

function outerPoint(i) {
  const angle = angleFor(i)
  return { x: cx.value + radius.value * Math.cos(angle), y: cy.value + radius.value * Math.sin(angle) }
}

function labelPoint(i) {
  const angle = angleFor(i)
  const r = radius.value + labelOffset.value
  return { x: cx.value + r * Math.cos(angle), y: cy.value + r * Math.sin(angle) }
}

function polygonPoints(fraction) {
  return axes.map((_, i) => {
    const angle = angleFor(i)
    const r = radius.value * fraction
    return `${cx.value + r * Math.cos(angle)},${cy.value + r * Math.sin(angle)}`
  }).join(' ')
}

const dataDots = computed(() =>
  axes.map((axis, i) => {
    const angle = angleFor(i)
    const val = (props.sessionDna[axis.key] ?? 0) / 100
    const r = radius.value * val
    return { x: cx.value + r * Math.cos(angle), y: cy.value + r * Math.sin(angle) }
  })
)

const dataPoints = computed(() => dataDots.value.map((p) => `${p.x},${p.y}`).join(' '))
</script>

<style lang="scss" scoped>
.session-dna-chart {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;

  &__radar {
    overflow: visible;
  }

  &__legend {
    width: 100%;
  }

  &__confirmed {
    margin-top: 4px;
  }

  &--full {
    padding: 16px;
  }

  &--compact {
    padding: 8px;
  }
}
</style>
