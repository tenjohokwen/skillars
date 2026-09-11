<template>
  <div>
    <div class="text-label q-mb-sm">{{ t('auth.coach.step3PerSessionPrice') }}</div>
    <q-input
      v-model.number="form.perSessionPrice"
      :label="t('auth.coach.step3PerSessionPrice')"
      outlined
      type="number"
      prefix="€"
      :rules="[(v) => (!!v && v > 0) || t('validation.required')]"
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
      class="q-mb-sm"
    />
    <div class="text-meta q-mb-lg">{{ t('auth.coach.step3SessionDurationHelper') }}</div>

    <div class="text-label q-mb-xs">{{ t('auth.coach.step3SessionPacks') }}</div>
    <div class="text-meta q-mb-sm">{{ t('auth.coach.step3PackHelper') }}</div>

    <div v-if="!form.sessionPacks.length" class="profile-builder__empty-state q-mb-sm">
      {{ t('auth.coach.step3PackEmpty') }}
    </div>

    <div
      v-for="(pack, i) in form.sessionPacks"
      :key="i"
      class="profile-builder__entry-card q-mb-sm"
    >
      <div class="row q-col-gutter-sm">
        <div class="col-12 col-sm-4">
          <q-input
            v-model.number="pack.sessionCount"
            :label="t('auth.coach.step3PackSessions')"
            outlined
            type="number"
            dense
          />
        </div>
        <div class="col-12 col-sm-4">
          <q-input
            v-model.number="pack.totalPrice"
            :label="t('auth.coach.step3PackPrice')"
            outlined
            type="number"
            dense
            prefix="€"
          />
        </div>
        <div class="col-10 col-sm-3">
          <q-input v-model="pack.label" :label="t('auth.coach.step3PackLabel')" outlined dense />
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

    <q-btn
      :label="t('auth.coach.step3AddPack')"
      class="btn-ghost"
      size="sm"
      icon="add"
      @click="addPack"
      unelevated
      no-caps
    />

    <div v-if="packError" class="text-negative text-caption q-mt-sm">{{ packError }}</div>

    <div class="q-mt-lg">
      <q-btn
        :label="t('common.next')"
        class="btn-accent"
        @click="submit"
        :loading="loading"
        unelevated
      />
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({ loading: Boolean })
const emit = defineEmits(['submit'])

// skillars-deferred-109 AC8.1: shown when submit() is blocked because a pack row was touched but
// would not survive the sessionCount>0 && totalPrice>0 filter.
const packError = ref('')

const form = reactive({
  perSessionPrice: null,
  // null means "inherit the platform default" — the state every coach starts in.
  sessionDurationMinutes: null,
  sessionPacks: [],
})

// A fixed list rather than free-typed minutes: chk_coach_pricing_session_duration (V93) rejects
// anything outside 15..240, and that rejection would surface as a generic step-error toast.
const DURATION_CHOICES = [30, 45, 60, 90, 120]
// Deferred-63 AC7: this screen is create-only today (form.sessionDurationMinutes always starts
// null), so an out-of-list value is unreachable through this UI — but a direct API call could still
// leave a coach's saved value outside DURATION_CHOICES. Defensive hardening only: if that ever
// happens, show the real value as its own synthetic option instead of rendering the select as
// unselected/reset, matching this project's other reachable-only-via-non-UI-path guards.
const durationOptions = computed(() => {
  const options = [
    { value: null, label: t('auth.coach.step3SessionDurationDefault') },
    ...DURATION_CHOICES.map((minutes) => ({
      value: minutes,
      label: t('auth.coach.step3SessionDurationMinutes', { minutes }),
    })),
  ]
  // skillars-deferred-109 AC8.2: defensive coercion only (no live trigger at HEAD — this screen is
  // create-only, sessionDurationMinutes always starts null). `includes` is strict-equality, so if a
  // future hydration path ever set a string '60', the unguarded check would append a duplicate
  // synthetic "60" option. Coerce before comparing. This does NOT normalise the submitted value.
  const current = Number(form.sessionDurationMinutes)
  if (form.sessionDurationMinutes != null && !DURATION_CHOICES.includes(current)) {
    options.push({ value: form.sessionDurationMinutes, label: String(form.sessionDurationMinutes) })
  }
  return options
})

function addPack() {
  form.sessionPacks.push({ sessionCount: null, totalPrice: null, label: '' })
}

function removePack(i) {
  form.sessionPacks.splice(i, 1)
  // skillars-deferred-109 code review: packError is otherwise only cleared by a SUCCESSFUL submit,
  // so removing (or fixing) the offending row left the red message standing under a pack list that
  // no longer has anything wrong with it. Clearing on any pack edit keeps the message tied to the
  // state that produced it.
  packError.value = ''
}

// skillars-deferred-109 AC8.1: a row the coach TOUCHED — any of sessionCount / totalPrice is
// non-null, or the label is non-blank — but that would silently drop out of the submit filter
// (needs sessionCount>0 && totalPrice>0). A fully-empty row ({null, null, label:''}) is not
// "touched" and is filtered without complaint.
// skillars-deferred-109 code review: `!= null` alone is not "the coach entered something".
// `v-model.number` on a type="number" q-input yields '' — NOT null — when the field is cleared
// (Vue's looseToNumber returns the original string when parseFloat gives NaN). A coach who typed
// 5 and then deleted it therefore left sessionCount === '', which `!= null` counted as touched
// while `'' > 0` is false: a row that looks completely empty on screen blocked submit forever,
// with the only escape being to notice the Remove button. Treat '' as untouched, like null.
function packFieldEntered(v) {
  return v != null && v !== ''
}
function packRowTouched(p) {
  return (
    packFieldEntered(p.sessionCount) ||
    packFieldEntered(p.totalPrice) ||
    (p.label != null && p.label.trim() !== '')
  )
}
function packRowValid(p) {
  return p.sessionCount > 0 && p.totalPrice > 0
}
const hasTouchedInvalidPack = computed(() =>
  form.sessionPacks.some((p) => packRowTouched(p) && !packRowValid(p)),
)

function submit() {
  if (!form.perSessionPrice || form.perSessionPrice <= 0) return
  // skillars-deferred-109 AC8.1: block rather than silently discard a half-filled pack row.
  if (hasTouchedInvalidPack.value) {
    packError.value = t('auth.coach.step3PackInvalid')
    return
  }
  packError.value = ''
  emit('submit', {
    perSessionPrice: form.perSessionPrice,
    sessionDurationMinutes: form.sessionDurationMinutes,
    sessionPacks: form.sessionPacks.filter(packRowValid).map((p) => ({
      sessionCount: p.sessionCount,
      totalPrice: p.totalPrice,
      label: p.label || null,
    })),
  })
}
</script>

<style lang="scss" scoped>
.profile-builder__empty-state {
  padding: 14px 16px;
  border: 1px dashed var(--border-medium);
  border-radius: 14px;
  color: var(--text-muted);
  font-size: 13px;
}

.profile-builder__entry-card {
  padding: 12px 12px 4px;
  background: var(--surface-glass);
  border: 1px solid var(--border-soft);
  border-radius: 14px;
}
</style>
