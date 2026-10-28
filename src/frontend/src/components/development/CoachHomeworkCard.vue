<template>
  <q-card class="q-mb-md" data-testid="coach-homework-card">
    <q-card-section>
      <div class="text-subtitle1">{{ $t('coach.playerDetailHomeworkTitle') }}</div>
      <div class="text-caption" style="color: var(--text-secondary)">
        {{ $t('coach.playerDetailHomeworkHint') }}
      </div>
    </q-card-section>
    <q-card-section class="relative-position">
      <q-inner-loading :showing="loading" />
      <div v-if="error && !loading" class="text-negative" data-testid="coach-homework-error">
        {{ $t('coach.playerDetailSectionError') }}
      </div>
      <template v-else-if="!loading">
        <div
          v-if="rows.length === 0"
          class="text-body2"
          style="color: var(--text-secondary)"
          data-testid="coach-homework-empty"
        >
          {{ $t('coach.playerDetailNoHomework') }}
        </div>
        <q-list v-else dense separator>
          <q-item v-for="row in rows" :key="row.assignmentId" data-testid="coach-homework-row">
            <q-item-section avatar>
              <q-icon
                :name="row.completed ? 'task_alt' : 'radio_button_unchecked'"
                :color="row.completed ? 'positive' : 'grey'"
              />
            </q-item-section>
            <q-item-section>
              <q-item-label>{{
                row.drillName || $t('coach.playerDetailHomeworkUnknownDrill')
              }}</q-item-label>
              <q-item-label caption>
                {{ $t('coach.playerDetailHomeworkAssigned', { date: formatDate(row.assignedAt) }) }}
              </q-item-label>
            </q-item-section>
            <q-item-section side>
              <q-item-label v-if="row.completed" class="text-positive">
                {{
                  $t('coach.playerDetailHomeworkCompletedOn', { date: formatDate(row.completedAt) })
                }}
              </q-item-label>
              <q-item-label v-else caption>{{ $t('coach.playerDetailHomeworkOpen') }}</q-item-label>
            </q-item-section>
          </q-item>
        </q-list>
      </template>
    </q-card-section>
  </q-card>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { homeworkApi } from 'src/api/homework.api'

/**
 * The homework THIS coach assigned the player, and whether the player (or a parent on their behalf)
 * has marked each done. GET /api/session/coach/players/{playerId}/homework — authorised by the shared
 * coach-access window (skillars-12-10), so a lapsed relationship shows this card's own error state
 * rather than blanking the page. Homework carries no due date in the model, so none is shown.
 */
const props = defineProps({
  playerId: { type: [String, Number], required: true },
})

const { locale } = useI18n()

const rows = ref([])
const loading = ref(false)
const error = ref(false)
let seq = 0

// toLocaleDateString(locale) rather than Quasar's date.formatDate: no Quasar lang pack is configured,
// so de/fr would otherwise show English month names (same choice as the neglected-skills card).
function formatDate(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleDateString(locale.value)
}

async function load() {
  if (!props.playerId) return
  const current = ++seq
  loading.value = true
  error.value = false
  try {
    const data = await homeworkApi.getCoachPlayerHomework(props.playerId)
    if (current === seq) rows.value = Array.isArray(data) ? data : []
  } catch {
    if (current === seq) {
      rows.value = []
      error.value = true
    }
  } finally {
    if (current === seq) loading.value = false
  }
}

onMounted(load)
watch(
  () => props.playerId,
  (id, old) => {
    if (id && id !== old) load()
  },
)
</script>
