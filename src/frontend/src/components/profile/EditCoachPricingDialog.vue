<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 500px; max-width: 600px">
      <q-card-section>
        <div class="text-h6">{{ $t('profile.updateCoachPricing') }}</div>
      </q-card-section>

      <q-card-section>
        <q-form @submit.prevent="handleSubmit">
          <q-input
            v-model.number="form.perSessionPrice"
            :label="t('auth.coach.step3PerSessionPrice')"
            outlined
            type="number"
            prefix="€"
            :rules="[(v) => (!!v && v > 0) || t('validation.required')]"
            :error="hasFieldError('perSessionPrice')"
            :error-message="getFieldError('perSessionPrice')"
            class="q-mb-lg"
          />

          <q-select
            v-model="form.sessionDurationMinutes"
            :options="durationOptions"
            option-value="value"
            option-label="label"
            emit-value
            map-options
            :label="t('auth.coach.step3SessionDuration')"
            outlined
            dense
            :error="hasFieldError('sessionDurationMinutes')"
            :error-message="getFieldError('sessionDurationMinutes')"
            class="q-mb-sm"
          />
          <div class="text-meta q-mb-lg">{{ t('auth.coach.step3SessionDurationHelper') }}</div>

          <div class="text-label q-mb-xs">{{ t('auth.coach.step3SessionPacks') }}</div>
          <div class="text-meta q-mb-sm">{{ t('auth.coach.step3PackHelper') }}</div>

          <div v-if="!form.sessionPacks.length" class="profile-dialog__empty-state q-mb-sm">
            {{ t('auth.coach.step3PackEmpty') }}
          </div>

          <div
            v-for="(pack, i) in form.sessionPacks"
            :key="i"
            class="profile-dialog__entry-card q-mb-sm"
          >
            <div class="row q-col-gutter-sm">
              <div class="col-12 col-sm-4">
                <q-input
                  v-model.number="pack.sessionCount"
                  :label="t('auth.coach.step3PackSessions')"
                  outlined
                  type="number"
                  min="1"
                  :max="PACK_MAX_SESSION_COUNT"
                  step="1"
                  dense
                  @update:model-value="packError = ''"
                />
              </div>
              <div class="col-12 col-sm-4">
                <q-input
                  v-model.number="pack.totalPrice"
                  :label="t('auth.coach.step3PackPrice')"
                  outlined
                  type="number"
                  min="0.01"
                  :max="PACK_MAX_TOTAL_PRICE"
                  step="0.01"
                  dense
                  prefix="€"
                  @update:model-value="packError = ''"
                />
              </div>
              <div class="col-10 col-sm-3">
                <q-input
                  v-model="pack.label"
                  :label="t('auth.coach.step3PackLabel')"
                  :maxlength="PACK_MAX_LABEL_LENGTH"
                  outlined
                  dense
                  @update:model-value="packError = ''"
                />
              </div>
              <div class="col-2 col-sm-1 flex items-center justify-end">
                <q-btn
                  icon="close"
                  flat
                  dense
                  round
                  :aria-label="t('auth.coach.step3RemovePack')"
                  @click="removePack(i)"
                />
              </div>
            </div>
          </div>

          <!-- skillars-deferred-168: one pack per coach - it is the offer families buy, the same one the
               "My Session Pack Offer" page edits. Saving here replaces it without that page's
               confirmation step; a stale second tab can therefore replace or restore it. -->
          <q-btn
            v-if="!form.sessionPacks.length"
            :label="t('auth.coach.step3AddPack')"
            class="btn-ghost"
            size="sm"
            icon="add"
            @click="addPack"
            unelevated
            no-caps
          />

          <div v-if="packError" class="text-negative text-caption q-mt-sm">{{ packError }}</div>
          <div v-else-if="packFieldError" class="text-negative text-caption q-mt-sm">
            {{ packFieldError }}
          </div>

          <q-banner
            v-if="hasError && !isValidationError"
            class="bg-negative text-white q-mt-md"
            rounded
          >
            {{ errorMessage }}
            <template v-if="helpCode">
              <br />
              <small>{{ $t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>

          <q-card-actions align="right" class="q-px-none q-pb-none">
            <q-btn type="button" flat :label="$t('common.cancel')" @click="close" />
            <q-btn
              type="submit"
              color="primary"
              :label="$t('common.save')"
              :loading="isSubmitting"
            />
          </q-card-actions>
        </q-form>
      </q-card-section>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, reactive, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { saveProfileBuilderStep } from 'src/api/marketplace.api'
import { useErrorHandler } from 'src/composables/useErrorHandler'
import {
  PACK_MAX_LABEL_LENGTH,
  PACK_MAX_SESSION_COUNT,
  PACK_MAX_TOTAL_PRICE,
  packErrorKey,
  packServerError,
  packsPayload,
} from 'src/utils/sessionPackForm'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  current: { type: Object, default: null },
})

const emit = defineEmits(['update:modelValue', 'updated'])

const { t } = useI18n()
const $q = useQuasar()
const {
  setError,
  clearError,
  hasError,
  errorMessage,
  isValidationError,
  helpCode,
  hasFieldError,
  getFieldError,
  fieldErrors,
} = useErrorHandler()

// skillars-deferred-168: bean validation reports the pack's fields as sessionPacks[0].totalPrice etc.,
// which an exact hasFieldError('sessionPacks') never matched, so a rejected pack showed nothing.
const packFieldError = computed(() => packServerError(fieldErrors.value))

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const packError = ref('')
const isSubmitting = ref(false)

const form = reactive({
  perSessionPrice: null,
  sessionDurationMinutes: null,
  sessionPacks: [],
})
// skillars-deferred-168: the pack list the form started with. A save sends the pack list only if the coach
// changed it (see packsPayload), so a price-only save never touches the offer, even when this form loaded
// stale or without the pack.
let initialPacks = []

const DURATION_CHOICES = [30, 45, 60, 90, 120]
const durationOptions = computed(() => {
  const options = [
    { value: null, label: t('auth.coach.step3SessionDurationDefault') },
    ...DURATION_CHOICES.map((minutes) => ({
      value: minutes,
      label: t('auth.coach.step3SessionDurationMinutes', { minutes }),
    })),
  ]
  const current = Number(form.sessionDurationMinutes)
  if (form.sessionDurationMinutes != null && !DURATION_CHOICES.includes(current)) {
    options.push({ value: form.sessionDurationMinutes, label: String(form.sessionDurationMinutes) })
  }
  return options
})

watch(
  () => props.current,
  (profile) => {
    if (profile) {
      form.perSessionPrice = profile.perSessionPrice ?? null
      form.sessionDurationMinutes = profile.sessionDurationMinutes ?? null
      form.sessionPacks = (profile.sessionPacks || []).map((p) => ({
        sessionCount: p.sessionCount,
        totalPrice: p.totalPrice,
        label: p.label || '',
      }))
      initialPacks = form.sessionPacks.map((p) => ({ ...p }))
    }
  },
  { immediate: true },
)

function addPack() {
  form.sessionPacks.push({ sessionCount: null, totalPrice: null, label: '' })
}

function removePack(i) {
  form.sessionPacks.splice(i, 1)
  packError.value = ''
}

const packErrorKeyNow = computed(() => form.sessionPacks.map(packErrorKey).find(Boolean) || null)

function close() {
  clearError()
  emit('update:modelValue', false)
}

async function handleSubmit() {
  clearError()
  if (!form.perSessionPrice || form.perSessionPrice <= 0) return
  if (packErrorKeyNow.value) {
    packError.value = t(packErrorKeyNow.value)
    return
  }
  packError.value = ''
  isSubmitting.value = true
  try {
    await saveProfileBuilderStep(3, {
      perSessionPrice: form.perSessionPrice,
      sessionDurationMinutes: form.sessionDurationMinutes,
      // null unless the coach changed the pack; [] only when they removed the pack they were shown.
      sessionPacks: packsPayload(initialPacks, form.sessionPacks),
    })

    $q.notify({ type: 'positive', message: t('success.infoChanged') })
    emit('updated')
    close()
  } catch (err) {
    setError(err)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.profile-dialog__empty-state {
  padding: 14px 16px;
  border: 1px dashed var(--border-medium);
  border-radius: 14px;
  color: var(--text-muted);
  font-size: 13px;
}

.profile-dialog__entry-card {
  padding: 12px 12px 4px;
  background: var(--surface-glass);
  border: 1px solid var(--border-soft);
  border-radius: 14px;
}
</style>
