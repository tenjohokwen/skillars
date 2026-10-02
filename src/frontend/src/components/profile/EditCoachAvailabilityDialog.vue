<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 500px; max-width: 600px">
      <q-card-section>
        <div class="text-h6">{{ $t('profile.updateCoachAvailability') }}</div>
      </q-card-section>

      <q-card-section>
        <q-form @submit.prevent="handleSubmit">
          <div v-if="!form.windows.length" class="profile-dialog__empty-state q-mb-sm">
            {{ t('auth.coach.step4WindowEmpty') }}
          </div>

          <div v-for="(win, i) in form.windows" :key="i" class="profile-dialog__entry-card q-mb-sm">
            <div class="row q-col-gutter-sm items-center">
              <div class="col-12 col-sm-4">
                <q-select
                  v-model="win.dayOfWeek"
                  :options="dayOptions"
                  option-value="value"
                  option-label="label"
                  emit-value
                  map-options
                  :label="t('auth.coach.step4Day')"
                  outlined
                  dense
                />
              </div>
              <div class="col-5 col-sm-3">
                <q-input
                  v-model="win.startTime"
                  :label="t('auth.coach.step4Start')"
                  outlined
                  dense
                  type="time"
                />
              </div>
              <div class="col-5 col-sm-3">
                <q-input
                  v-model="win.endTime"
                  :label="t('auth.coach.step4End')"
                  outlined
                  dense
                  type="time"
                />
              </div>
              <div class="col-2 flex items-center justify-end">
                <q-btn
                  icon="close"
                  flat
                  dense
                  round
                  :aria-label="t('auth.coach.step4RemoveWindow')"
                  @click="removeWindow(i)"
                />
              </div>
            </div>
          </div>

          <q-btn
            :label="t('auth.coach.step4AddWindow')"
            class="btn-ghost"
            size="sm"
            icon="add"
            @click="addWindow"
            unelevated
            no-caps
          />

          <div class="text-label q-mb-sm q-mt-lg">{{ t('auth.coach.step4SectionTimezone') }}</div>
          <!-- skillars-deferred-139 review D1, corrected by skillars-deferred-140 AC1.1: read-only —
               as of deferred-140, saveStep4 unconditionally overwrites every window's zone with the
               profile's own (Option B); before deferred-140 it still accepted and persisted
               whatever per-window value the request sent (ProfileBuilderStep4.vue's own picker was
               the one place that could still diverge it). Making the picker editable here implied a
               per-window zone this dialog never actually wrote, and silently re-zoned any window
               save. The zone now belongs to Identity & Location (Step 1) only; this dialog displays
               it and keeps stamping it onto every window, matching AvailabilityService.addWindow's
               own behavior. -->
          <div class="text-body q-mb-xs">{{ form.canonicalTimezone || '—' }}</div>
          <div class="text-meta q-mb-sm">{{ t('profile.availabilityTimezoneReadonlyHint') }}</div>
          <div v-if="hasFieldError('windows')" class="text-negative text-caption q-mb-sm">
            {{ getFieldError('windows') }}
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
} = useErrorHandler()

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const dayOptions = computed(() => [
  { label: t('auth.coach.weekdayMonday'), value: 1 },
  { label: t('auth.coach.weekdayTuesday'), value: 2 },
  { label: t('auth.coach.weekdayWednesday'), value: 3 },
  { label: t('auth.coach.weekdayThursday'), value: 4 },
  { label: t('auth.coach.weekdayFriday'), value: 5 },
  { label: t('auth.coach.weekdaySaturday'), value: 6 },
  { label: t('auth.coach.weekdaySunday'), value: 7 },
])

const form = reactive({ windows: [], canonicalTimezone: null })
const isSubmitting = ref(false)

watch(
  () => props.current,
  (profile) => {
    if (profile) {
      form.windows = (profile.availabilityWindows || []).map((w) => ({
        dayOfWeek: w.dayOfWeek,
        startTime: w.startTime,
        endTime: w.endTime,
      }))
      form.canonicalTimezone = profile.canonicalTimezone || null
    }
  },
  { immediate: true },
)

function addWindow() {
  form.windows.push({ dayOfWeek: null, startTime: '', endTime: '' })
}

function removeWindow(i) {
  form.windows.splice(i, 1)
}

function close() {
  clearError()
  emit('update:modelValue', false)
}

async function handleSubmit() {
  clearError()
  const valid =
    form.windows.length > 0 && form.windows.every((w) => w.dayOfWeek && w.startTime && w.endTime)
  if (!valid || !form.canonicalTimezone) return
  isSubmitting.value = true
  try {
    await saveProfileBuilderStep(4, {
      windows: form.windows.map((w) => ({
        dayOfWeek: w.dayOfWeek,
        startTime: w.startTime,
        endTime: w.endTime,
        canonicalTimezone: form.canonicalTimezone,
      })),
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
  padding: 12px;
  background: var(--surface-glass);
  border: 1px solid var(--border-soft);
  border-radius: 14px;
}
</style>
