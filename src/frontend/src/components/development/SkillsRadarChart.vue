<template>
  <div class="skills-radar-chart">
    <svg
      viewBox="0 0 400 400"
      preserveAspectRatio="xMidYMid meet"
      aria-hidden="true"
      focusable="false"
      style="width: 100%; max-width: 500px; display: block; margin: 0 auto"
    >
      <!-- Concentric reference circles -->
      <circle
        v-for="r in [RADIUS * 0.25, RADIUS * 0.5, RADIUS * 0.75, RADIUS]"
        :key="r"
        :cx="CENTER_X"
        :cy="CENTER_Y"
        :r="r"
        fill="none"
        stroke="var(--glass-border)"
        stroke-width="1"
      />

      <!-- Axis lines from center to each node -->
      <line
        v-for="(node, i) in nodePositions"
        :key="`axis-${i}`"
        :x1="CENTER_X"
        :y1="CENTER_Y"
        :x2="node.x"
        :y2="node.y"
        stroke="var(--glass-border)"
        stroke-width="1"
      />

      <!-- Ghost baseline polygon -->
      <polygon
        v-if="showBaseline && hasBaseline"
        :points="baselinePoints"
        fill="none"
        stroke="var(--text-secondary)"
        stroke-width="1"
        stroke-dasharray="4,2"
        opacity="0.5"
      />

      <!-- Current score polygon — requires ≥3 points to form a valid shape -->
      <polygon
        v-if="activeSkills.length >= 3"
        :points="polygonPoints"
        fill="var(--accent-primary)"
        fill-opacity="0.15"
        stroke="var(--accent-primary)"
        stroke-width="2"
      />

      <!-- Empty state: no scored skills -->
      <text
        v-if="activeSkills.length === 0"
        :x="CENTER_X"
        :y="CENTER_Y"
        text-anchor="middle"
        dominant-baseline="middle"
        fill="var(--text-secondary)"
        font-size="14"
      >
        {{ $t('development.radar.noAssessmentsYet') }}
      </text>

      <!-- Degenerate polygon warning: 1 or 2 skills selected produces an invisible shape -->
      <text
        v-else-if="activeSkills.length < 3"
        :x="CENTER_X"
        :y="CENTER_Y"
        text-anchor="middle"
        dominant-baseline="middle"
        fill="var(--text-secondary)"
        font-size="14"
      >
        {{ $t('development.radar.minSkillsRequired') }}
      </text>

      <!-- Node labels and badges -->
      <g
        v-for="(node, i) in nodePositions"
        :key="`node-${i}`"
        class="radar-node"
        :style="{ cursor: readonly ? 'default' : 'pointer' }"
        @click="toggleSkill(node.skill.skillCode)"
      >
        <!-- Score badge background -->
        <circle
          :cx="node.x"
          :cy="node.y"
          r="16"
          fill="var(--glass-bg)"
          stroke="var(--glass-border)"
          stroke-width="1"
        />

        <!-- Skill code label -->
        <text
          :x="node.x"
          :y="node.y - 3"
          text-anchor="middle"
          dominant-baseline="middle"
          fill="var(--text-primary)"
          font-size="7"
          font-weight="600"
        >{{ node.skill.skillCode }}</text>

        <!-- Score value -->
        <text
          :x="node.x"
          :y="node.y + 6"
          text-anchor="middle"
          dominant-baseline="middle"
          fill="var(--accent-primary)"
          font-size="7"
        >{{ node.skill.compositeScore != null ? node.skill.compositeScore : '—' }}</text>

        <!-- Confidence dot -->
        <circle
          :cx="node.x + 14"
          :cy="node.y - 10"
          r="4"
          :fill="confidenceDotFill(node.skill.entryCount) === 'filled' ? 'var(--accent-primary)' : 'none'"
          :stroke="'var(--accent-primary)'"
          stroke-width="1.5"
          :opacity="confidenceDotFill(node.skill.entryCount) === 'empty' ? 0.4 : 1"
        />
        <!-- Half-fill dot for 1-2 entries -->
        <path
          v-if="confidenceDotFill(node.skill.entryCount) === 'half'"
          :d="`M ${node.x + 14} ${node.y - 14} A 4 4 0 0 1 ${node.x + 14} ${node.y - 6} Z`"
          fill="var(--accent-primary)"
        />

        <!-- Delta indicator -->
        <text
          v-if="deltaText(node.skill) !== null"
          :x="node.x"
          :y="node.y + 26"
          text-anchor="middle"
          font-size="7"
          :fill="deltaColor(node.skill)"
        >{{ deltaText(node.skill) }}</text>

        <!-- Last-updated tooltip -->
        <q-tooltip v-if="node.skill.lastUpdatedAt">
          {{ $t('development.radar.lastUpdatedTooltip', { date: new Date(node.skill.lastUpdatedAt).toLocaleDateString() }) }}
        </q-tooltip>
      </g>
    </svg>

    <!-- Accessible screen reader alternative — all 15 skills regardless of polygon selection -->
    <!-- NOTE: entry_count counts total rows across all assessment types and coaches; a filled dot
         may show even when the composite is capped (e.g. 3 OBJECTIVE-only assessments). -->
    <table class="sr-only" aria-label="Skills Radar Data">
      <thead>
        <tr>
          <th>{{ $t('development.radar.accessibleTable.skill') }}</th>
          <th>{{ $t('development.radar.accessibleTable.currentScore') }}</th>
          <th>{{ $t('development.radar.accessibleTable.baselineScore') }}</th>
          <th>{{ $t('development.radar.accessibleTable.delta') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in skills" :key="s.skillCode">
          <td>{{ s.skillCode }}</td>
          <td>{{ s.compositeScore ?? '—' }}</td>
          <td>{{ s.baselineScore ?? '—' }}</td>
          <td>{{ deltaText(s) ?? '—' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { computed, toRefs } from 'vue'

const props = defineProps({
  skills: { type: Array, required: true },
  selectedSkillCodes: { type: Array, default: () => [] },
  showBaseline: { type: Boolean, default: false },
  readonly: { type: Boolean, default: false },
})

const emit = defineEmits(['update:selectedSkillCodes'])

// toRefs required in <script setup> so computed references resolve correctly
const { skills, selectedSkillCodes, showBaseline, readonly } = toRefs(props)

const CENTER_X = 200
const CENTER_Y = 200
const RADIUS = 160

const activeSkills = computed(() => {
  if (selectedSkillCodes.value.length > 0) {
    return skills.value.filter((s) => selectedSkillCodes.value.includes(s.skillCode))
  }
  return skills.value.filter((s) => s.compositeScore !== null)
})

const hasBaseline = computed(() => activeSkills.value.some((s) => s.baselineScore !== null))

function toPoint(index, total, scoreOrNull, maxScore = 100) {
  const angle = (2 * Math.PI * index) / total - Math.PI / 2
  // Clamp score to [0, maxScore] — guards against out-of-range composites plotting outside boundary
  const clamped = scoreOrNull !== null ? Math.max(0, Math.min(scoreOrNull, maxScore)) : 0
  const r = (clamped / maxScore) * RADIUS
  return {
    x: CENTER_X + r * Math.cos(angle),
    y: CENTER_Y + r * Math.sin(angle),
  }
}

const polygonPoints = computed(() =>
  activeSkills.value
    .map((s, i) => {
      const pt = toPoint(i, activeSkills.value.length, s.compositeScore)
      return `${pt.x},${pt.y}`
    })
    .join(' '),
)

const baselinePoints = computed(() =>
  activeSkills.value
    .map((s, i) => {
      // Fall back to compositeScore for skills without a baseline so those vertices
      // stay at their current score position rather than collapsing to the center
      const pt = toPoint(i, activeSkills.value.length, s.baselineScore ?? s.compositeScore)
      return `${pt.x},${pt.y}`
    })
    .join(' '),
)

const nodePositions = computed(() =>
  activeSkills.value.map((s, i) => {
    const angle = (2 * Math.PI * i) / activeSkills.value.length - Math.PI / 2
    return {
      x: CENTER_X + (RADIUS + 20) * Math.cos(angle),
      y: CENTER_Y + (RADIUS + 20) * Math.sin(angle),
      skill: s,
    }
  }),
)

function confidenceDotFill(entryCount) {
  if (!entryCount || entryCount === 0) return 'empty'
  if (entryCount <= 2) return 'half'
  return 'filled'
}

function deltaText(s) {
  if (s.baselineScore === null || s.compositeScore === null) return null
  const delta = (s.compositeScore - s.baselineScore).toFixed(0)
  const num = Number(delta)
  if (num > 0) return `↑ +${delta}`
  if (num < 0) return `↓ ${delta}`
  return `= ${delta}`
}

function deltaColor(s) {
  if (s.baselineScore === null || s.compositeScore === null) return 'var(--text-secondary)'
  const delta = s.compositeScore - s.baselineScore
  if (delta > 0) return 'var(--accent-primary)'
  if (delta < 0) return 'var(--accent-danger)'
  return 'var(--text-secondary)'
}

function toggleSkill(skillCode) {
  if (readonly.value) return
  const current = selectedSkillCodes.value
  const updated = current.includes(skillCode)
    ? current.filter((c) => c !== skillCode)
    : [...current, skillCode]
  emit('update:selectedSkillCodes', updated)
}
</script>

<style scoped>
.skills-radar-chart {
  position: relative;
}

.radar-node {
  transition: opacity 0.15s;
}

.radar-node:hover {
  opacity: 0.8;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
}
</style>
