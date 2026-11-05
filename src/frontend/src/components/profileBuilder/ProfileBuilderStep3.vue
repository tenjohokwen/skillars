<template>
  <div>
    <div class="text-label q-mb-sm">{{ t('auth.coach.step3PerSessionPrice') }}</div>
    <q-input
      ref="priceInput"
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
            min="1"
            :max="PACK_MAX_SESSION_COUNT"
            step="1"
            dense
            @update:model-value="onPackEdited"
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
            @update:model-value="onPackEdited"
          />
        </div>
        <div class="col-10 col-sm-3">
          <q-input
            v-model="pack.label"
            :label="t('auth.coach.step3PackLabel')"
            :maxlength="PACK_MAX_LABEL_LENGTH"
            outlined
            dense
            @update:model-value="onPackEdited"
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

    <!-- skillars-deferred-168: one pack per coach - it becomes the offer families can buy. -->
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
import {
  PACK_MAX_LABEL_LENGTH,
  PACK_MAX_SESSION_COUNT,
  PACK_MAX_TOTAL_PRICE,
  packErrorKey,
  packsPayload,
} from 'src/utils/sessionPackForm'

const { t } = useI18n()

defineProps({ loading: Boolean })
const emit = defineEmits(['submit'])

// skillars-deferred-109 AC8.1 / skillars-deferred-168: shown when submit() is blocked because the pack
// was touched but is not one the coach's offer can hold (see packErrorKey).
const packError = ref('')
// The price rule only renders after the field is edited, so submit() calls validate() itself;
// otherwise Next with a never-touched price stays on step 3 with no message.
const priceInput = ref(null)

// skillars-deferred-168: this screen always starts with no pack (it never shows the current offer), and the
// builder's step rail lets a coach come back to it. Sending [] would WITHDRAW an offer they set up meanwhile
// (here or on the "My Session Pack Offer" page), so the step sends a pack only when the coach filled one in,
// and sessionPacks: null (leave the offer untouched) otherwise, including after an added-then-emptied or
// added-then-removed row. See packsPayload.

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

function onPackEdited() {
  packError.value = ''
}

const packErrorKeyNow = computed(() => form.sessionPacks.map(packErrorKey).find(Boolean) || null)

function submit() {
  if (!form.perSessionPrice || form.perSessionPrice <= 0) {
    priceInput.value?.validate()
    return
  }
  // skillars-deferred-109 AC8.1: block rather than silently discard a half-filled pack.
  if (packErrorKeyNow.value) {
    packError.value = t(packErrorKeyNow.value)
    return
  }
  packError.value = ''
  emit('submit', {
    perSessionPrice: form.perSessionPrice,
    sessionDurationMinutes: form.sessionDurationMinutes,
    sessionPacks: packsPayload([], form.sessionPacks),
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
