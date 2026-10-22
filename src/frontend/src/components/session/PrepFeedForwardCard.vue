<template>
  <q-card flat bordered class="prep-feed-forward-card q-mb-sm">
    <q-card-section v-if="prep.suggestedWhatsNext" data-testid="suggestion-section">
      <div class="text-caption text-weight-bold">{{ t('coach.planNoteSuggestionTitle') }}</div>
      <div class="text-body2 q-mt-xs" style="white-space: pre-wrap" data-testid="suggestion-text">
        {{ prep.suggestedWhatsNext }}
      </div>
      <div
        v-if="suggestionDateLabel"
        class="text-caption"
        style="color: var(--text-secondary)"
        data-testid="suggestion-date"
      >
        {{ t('coach.planNoteSuggestionFrom', { date: suggestionDateLabel }) }}
      </div>

      <div v-if="applyState === 'already'" class="text-caption q-mt-sm" data-testid="apply-already">
        {{ t('coach.planNoteAlready') }}
      </div>
      <div v-else-if="applyState === 'use' || applyState === 'append' || applyState === 'tooLong'">
        <q-btn
          unelevated
          color="primary"
          size="sm"
          class="q-mt-sm"
          :label="applyState === 'use' ? t('coach.planNoteUse') : t('coach.planNoteAppend')"
          :loading="applying"
          :disable="applyState === 'tooLong' || panelDirty"
          data-testid="apply-button"
          @click="apply"
        />
        <div
          v-if="applyState === 'tooLong'"
          class="text-caption q-mt-xs"
          data-testid="apply-too-long"
        >
          {{ t('coach.planNoteTooLong') }}
        </div>
        <div v-else-if="panelDirty" class="text-caption q-mt-xs" data-testid="apply-dirty">
          {{ t('coach.planNoteDirty') }}
        </div>
      </div>
      <!-- applyState === 'locked': no Apply at all - the write would be rejected. -->
    </q-card-section>

    <q-card-section>
      <CoachPlanNotePanel
        :booking-id="props.bookingId"
        :note="note"
        :editable="prep.coachPlanNoteEditable"
        :busy="applying"
        @saved="onSaved"
        @dirty-change="panelDirty = $event"
      />
    </q-card-section>
  </q-card>
</template>

<script setup>
// skillars-12-6 AC13 (G5): "what's next" feed-forward inside the Session Builder. Shows last time's
// suggestion and lets the coach carry it into the booking's coach plan note - never overwriting.
// (A session's developmentFocus is a list of validated skill codes and cannot hold free text, so the
// apply target is the plan note; owner-approved 2026-10-22.)
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { setCoachPlanNote } from 'src/api/booking.api'
import { planNoteErrorMessage } from 'src/utils/planNoteError'
import CoachPlanNotePanel from 'src/components/booking/CoachPlanNotePanel.vue'

const PLAN_NOTE_MAX_LENGTH = 500

const props = defineProps({
  bookingId: { type: String, required: true },
  prep: { type: Object, required: true },
})

const { t, locale } = useI18n()
const $q = useQuasar()

const note = ref(props.prep.coachPlanNote ?? null)
const panelDirty = ref(false)
const applying = ref(false)

watch(
  () => props.prep.coachPlanNote,
  (next) => {
    note.value = next ?? null
  },
)

const suggestionDateLabel = computed(() =>
  props.prep.suggestedWhatsNextSessionDate
    ? new Date(props.prep.suggestedWhatsNextSessionDate).toLocaleDateString(locale.value)
    : '',
)

// 'locked'  - the write would be rejected (status/time window), so no Apply at all
// 'already' - the note already contains the suggestion (server-sanitised on both sides)
// 'use'     - empty note: set it
// 'append'  - non-empty note: append "\n" + suggestion
// 'tooLong' - appending would exceed the 500-char column; disabled with a caption
const applyState = computed(() => {
  if (!props.prep.coachPlanNoteEditable) return 'locked'
  const suggestion = props.prep.suggestedWhatsNext?.trim()
  if (!suggestion) return 'locked'
  const current = note.value ?? ''
  if (current.trim() === '') return 'use'
  if (current.includes(suggestion)) return 'already'
  return `${current}\n${suggestion}`.length > PLAN_NOTE_MAX_LENGTH ? 'tooLong' : 'append'
})

function onSaved(stored) {
  note.value = stored
}

async function apply() {
  const suggestion = props.prep.suggestedWhatsNext.trim()
  const current = note.value ?? ''
  const combined = current.trim() === '' ? suggestion : `${current}\n${suggestion}`
  applying.value = true
  try {
    const res = await setCoachPlanNote(props.bookingId, combined)
    // Always show exactly what the server stored (it sanitises and truncates).
    note.value = res?.planNote ?? null
    $q.notify({ type: 'positive', message: t('coach.planNoteSaved') })
  } catch (err) {
    $q.notify({ type: 'negative', message: planNoteErrorMessage(err, t) })
  } finally {
    applying.value = false
  }
}
</script>
