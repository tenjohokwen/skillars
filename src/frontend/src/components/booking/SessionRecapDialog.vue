<template>
  <q-dialog :model-value="modelValue" @update:model-value="onDialogToggle">
    <q-card class="session-recap-dialog" style="min-width: 360px; max-width: 560px; width: 100%">
      <q-card-section class="row items-center q-pb-none">
        <div class="text-h6">{{ t('booking.recap.dialogTitle', { name: playerName }) }}</div>
        <q-space />
        <q-btn
          icon="close"
          flat
          round
          dense
          :aria-label="t('booking.recap.close')"
          @click="close"
        />
      </q-card-section>

      <q-card-section v-if="loading" class="flex flex-center q-py-lg">
        <q-spinner size="40px" />
      </q-card-section>

      <q-card-section v-else-if="loadError" class="text-negative" data-testid="recap-load-error">
        <div>{{ t('booking.recap.loadError') }}</div>
        <q-btn
          flat
          dense
          class="q-mt-sm"
          color="primary"
          :label="t('booking.recap.retry')"
          data-testid="recap-load-retry"
          @click="load"
        />
      </q-card-section>

      <template v-else>
        <q-card-section>
          <div
            v-if="!editable"
            class="text-body2 q-mb-md"
            style="color: var(--text-secondary)"
            data-testid="recap-closed"
          >
            <template v-if="notEditableReason === 'booking.recapNotEditableErased'">
              {{ t('booking.recap.erasedError') }}
            </template>
            <template v-else-if="notEditableReason === 'booking.recapNotEditableDisputed'">
              {{ t('booking.recap.disputedError') }}
            </template>
            <template v-else-if="notEditableReason === 'booking.wrapUpAwaited'">
              {{ t('booking.recap.wrapUpAwaitedError') }}
            </template>
            <template v-else-if="untilLabel">
              {{ t('booking.recap.closedWindow', { date: untilLabel }) }}
            </template>
            <template v-else>
              {{ t('booking.recap.closedWindowNoDate') }}
            </template>
          </div>
          <div
            v-else-if="untilLabel"
            class="text-caption q-mb-md"
            style="color: var(--text-secondary)"
          >
            {{ t('booking.recap.editableUntil', { date: untilLabel }) }}
          </div>

          <q-input
            v-model="whatWeDid"
            type="textarea"
            outlined
            autogrow
            maxlength="500"
            counter
            class="q-mb-md"
            data-testid="recap-what-we-did"
            :label="t('booking.recap.whatWeDidLabel')"
            :readonly="!editable"
            :disable="saving"
          />
          <q-input
            v-model="whatsNext"
            type="textarea"
            outlined
            autogrow
            maxlength="500"
            counter
            class="q-mb-md"
            data-testid="recap-whats-next"
            :label="t('booking.recap.whatsNextLabel')"
            :readonly="!editable"
            :disable="saving"
          />
          <q-input
            v-model="whatWentWell"
            type="textarea"
            outlined
            autogrow
            maxlength="500"
            counter
            data-testid="recap-what-went-well"
            :label="t('booking.recap.whatWentWellLabel')"
            :readonly="!editable"
            :disable="saving"
          />
        </q-card-section>

        <q-card-actions align="right" class="q-pa-md">
          <q-btn flat :label="t('booking.recap.close')" :disable="saving" @click="close" />
          <q-btn
            v-if="editable"
            unelevated
            color="primary"
            data-testid="recap-save"
            :label="t('booking.recap.save')"
            :loading="saving"
            :disable="!canSave"
            @click="save"
          />
        </q-card-actions>
      </template>
    </q-card>
  </q-dialog>
</template>

<script setup>
// skillars-12-7 AC6: add / correct a completed session's recap. Loads GET /api/bookings/{id}/recap on
// open and saves with PUT. `editable` comes from the server's single editability check (window and
// erasure), so this dialog never decides that itself. Strings via vue-i18n with literal keys only
// (i18nTemplateKeyResolutionSpec cannot resolve a dynamically-built key).
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { getSessionRecap, updateSessionRecap } from 'src/api/booking.api'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  bookingId: { type: String, default: null },
  playerName: { type: String, default: '' },
  // skillars-deferred-163 AC2.2: recap text recovered from a wrap-up that could no longer be
  // submitted (the booking was force-completed first). Applied once load() resolves, only into
  // fields the GET response left empty — a fresh force-complete placeholder's recap fields are
  // always empty, so this never clobbers real saved text. Shape: { whatWeDid, whatsNext, whatWentWell }.
  prefill: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue', 'saved'])

const { t, locale } = useI18n()
const $q = useQuasar()

const loading = ref(false)
const loadError = ref(false)
const saving = ref(false)
const editable = ref(false)
const editableUntil = ref(null)
// Server-supplied reason when `editable` is false ('booking.recapEditWindowClosed',
// 'booking.recapNotEditableErased' or 'booking.recapNotEditableDisputed'); picks which read-only message to
// show, never re-decides editability.
const notEditableReason = ref(null)
const whatWeDid = ref('')
const whatsNext = ref('')
const whatWentWell = ref('')

// Stale-response guard: a slow GET for a previously opened booking must not overwrite the fields of
// the booking opened since (or of a dialog that has been closed and reopened).
let loadToken = 0

const untilLabel = computed(() => {
  if (!editableUntil.value) return ''
  const date = new Date(editableUntil.value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium' }).format(date)
})

const canSave = computed(
  () =>
    editable.value &&
    !saving.value &&
    !!whatWeDid.value.trim() &&
    !!whatsNext.value.trim() &&
    whatWeDid.value.length <= 500 &&
    whatsNext.value.length <= 500 &&
    whatWentWell.value.length <= 500,
)

async function load() {
  const token = ++loadToken
  loading.value = true
  loadError.value = false
  try {
    const res = await getSessionRecap(props.bookingId)
    if (token !== loadToken) return
    const data = res?.data ?? res
    whatWeDid.value = data?.whatWeDid ?? ''
    whatsNext.value = data?.whatsNext ?? ''
    whatWentWell.value = data?.whatWentWell ?? ''
    editable.value = data?.editable === true
    editableUntil.value = data?.editableUntil ?? null
    notEditableReason.value = data?.notEditableReason ?? null
    applyPrefillIntoEmptyFields()
  } catch {
    if (token !== loadToken) return
    loadError.value = true
  } finally {
    if (token === loadToken) loading.value = false
  }
}

// skillars-deferred-163 AC2.2: only into fields the load left empty -- never overwrites real,
// already-saved recap text. Code review 2026-10-27: and only when the recap can actually be saved --
// unsaved text in a read-only dialog would look like a saved recap.
function applyPrefillIntoEmptyFields() {
  const prefill = props.prefill
  if (!prefill || !editable.value) return
  if (!whatWeDid.value && prefill.whatWeDid) whatWeDid.value = prefill.whatWeDid
  if (!whatsNext.value && prefill.whatsNext) whatsNext.value = prefill.whatsNext
  if (!whatWentWell.value && prefill.whatWentWell) whatWentWell.value = prefill.whatWentWell
}

function saveErrorMessage(err) {
  const errorKey = err?.response?.data?.errorMsg?.errorKey
  if (errorKey === 'booking.recapEditWindowClosed') return t('booking.recap.windowClosedError')
  if (errorKey === 'booking.recapNotEditableErased') return t('booking.recap.erasedError')
  if (errorKey === 'booking.recapNotEditableDisputed') return t('booking.recap.disputedError')
  if (errorKey === 'booking.wrapUpAwaited') return t('booking.recap.wrapUpAwaitedError')
  return t('booking.recap.saveError')
}

async function save() {
  if (!canSave.value) return
  saving.value = true
  let saved = false
  try {
    await updateSessionRecap(props.bookingId, {
      whatWeDid: whatWeDid.value.trim(),
      whatsNext: whatsNext.value.trim(),
      whatWentWell: whatWentWell.value.trim() || null,
    })
    saved = true
  } catch (err) {
    $q.notify({ type: 'negative', message: saveErrorMessage(err) })
  } finally {
    saving.value = false
  }
  // Outside the try/catch so a throwing parent listener on a successful save is never reported as a
  // save failure.
  if (saved) {
    $q.notify({ type: 'positive', message: t('booking.recap.saved') })
    emit('saved', props.bookingId)
    emit('update:modelValue', false)
  }
}

function close() {
  if (saving.value) return
  emit('update:modelValue', false)
}

function onDialogToggle(value) {
  if (!value && saving.value) return
  emit('update:modelValue', value)
}

watch(
  () => [props.modelValue, props.bookingId],
  ([open, id]) => {
    if (open && id) {
      load()
    } else {
      loadToken++
    }
  },
  { immediate: true },
)
</script>
