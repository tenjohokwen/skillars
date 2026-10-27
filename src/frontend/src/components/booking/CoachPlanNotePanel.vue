<template>
  <div class="coach-plan-note-panel">
    <div class="text-caption text-weight-bold">{{ t('coach.planNoteLabel') }}</div>

    <template v-if="editable">
      <q-input
        v-model="draft"
        type="textarea"
        autogrow
        outlined
        dense
        maxlength="500"
        counter
        class="q-mt-xs"
        :placeholder="t('coach.planNotePlaceholder')"
        :disable="saving || busy || locallyLocked"
        data-testid="plan-note-input"
      />
      <div class="row items-center q-mt-xs">
        <q-btn
          unelevated
          color="primary"
          size="sm"
          :label="t('coach.planNoteSave')"
          :loading="saving"
          :disable="!dirty || busy || locallyLocked"
          data-testid="plan-note-save"
          @click="save"
        />
      </div>
    </template>
    <template v-else>
      <div
        class="text-body2 q-mt-xs"
        style="white-space: pre-wrap"
        data-testid="plan-note-readonly"
      >
        {{ note || t('coach.planNoteEmpty') }}
      </div>
      <div class="text-caption q-mt-xs" style="color: var(--text-secondary)">
        {{ t('coach.planNoteLocked') }}
      </div>
    </template>
  </div>
</template>

<script setup>
// skillars-12-6 AC6: the coach's private plan note editor, shared by SessionPrepCard and the Session
// Builder. The note comes from the prep response, so it is re-shown on reopen. Strings via vue-i18n.
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { setCoachPlanNote } from 'src/api/booking.api'
import { planNoteErrorMessage, isLockedPlanNoteError } from 'src/utils/planNoteError'

const props = defineProps({
  bookingId: { type: String, required: true },
  note: { type: String, default: null },
  editable: { type: Boolean, default: false },
  // An outside write to this note is in flight (the Session Builder's Apply). Locks the input so the
  // keystrokes typed meanwhile are not silently replaced when the new note arrives via `note`.
  busy: { type: Boolean, default: false },
})
const emit = defineEmits(['saved', 'dirty-change', 'locked'])

const { t } = useI18n()
const $q = useQuasar()

const draft = ref(props.note ?? '')
const saving = ref(false)
// skillars-deferred-165 AC8: a locked 403 means this will never succeed again for this booking —
// flip the editor non-interactive instead of leaving it live after the toast fades. `editable`
// itself stays a read-only prop; this is a separate, client-only guard layered on top of it.
const locallyLocked = ref(false)

const dirty = computed(() => (draft.value ?? '') !== (props.note ?? ''))

// A new note from the parent (after a save, or an apply from the suggestion card) replaces the draft.
watch(
  () => props.note,
  (next) => {
    draft.value = next ?? ''
  },
)
watch(dirty, (value) => emit('dirty-change', value), { immediate: true })

// The panel is not remounted on every booking switch (SessionPrepCard has no :key), so a stale
// lock from a previous booking must not bleed into the next one.
watch(
  () => props.bookingId,
  () => {
    locallyLocked.value = false
  },
)

async function save() {
  saving.value = true
  try {
    const res = await setCoachPlanNote(props.bookingId, draft.value)
    // `res` is the server's sanitised/truncated value - always show what was actually stored.
    const stored = res?.planNote ?? null
    draft.value = stored ?? ''
    emit('saved', stored)
    $q.notify({ type: 'positive', message: t('coach.planNoteSaved') })
  } catch (err) {
    if (isLockedPlanNoteError(err)) {
      locallyLocked.value = true
      // Lets a host with its own write path to the same note (PrepFeedForwardCard's Apply) lock too.
      emit('locked')
    }
    $q.notify({ type: 'negative', message: planNoteErrorMessage(err, t) })
  } finally {
    saving.value = false
  }
}
</script>
