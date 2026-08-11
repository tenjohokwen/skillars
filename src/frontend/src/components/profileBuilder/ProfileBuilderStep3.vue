<template>
  <div>
    <div class="text-label q-mb-sm">{{ t('auth.coach.step3PerSessionPrice') }}</div>
    <q-input
      v-model.number="form.perSessionPrice"
      :label="t('auth.coach.step3PerSessionPrice')"
      outlined
      type="number"
      prefix="€"
      :rules="[v => !!v && v > 0 || t('validation.required')]"
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

    <div v-for="(pack, i) in form.sessionPacks" :key="i" class="profile-builder__entry-card q-mb-sm">
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
import { computed, reactive } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({ loading: Boolean })
const emit = defineEmits(['submit'])

const form = reactive({
  perSessionPrice: null,
  // null means "inherit the platform default" — the state every coach starts in.
  sessionDurationMinutes: null,
  sessionPacks: [],
})

// A fixed list rather than free-typed minutes: chk_coach_pricing_session_duration (V93) rejects
// anything outside 15..240, and that rejection would surface as a generic step-error toast.
const DURATION_CHOICES = [30, 45, 60, 90, 120]
const durationOptions = computed(() => [
  { value: null, label: t('auth.coach.step3SessionDurationDefault') },
  ...DURATION_CHOICES.map(minutes => ({
    value: minutes,
    label: t('auth.coach.step3SessionDurationMinutes', { minutes }),
  })),
])

function addPack() {
  form.sessionPacks.push({ sessionCount: null, totalPrice: null, label: '' })
}

function removePack(i) {
  form.sessionPacks.splice(i, 1)
}

function submit() {
  if (!form.perSessionPrice || form.perSessionPrice <= 0) return
  emit('submit', {
    perSessionPrice: form.perSessionPrice,
    sessionDurationMinutes: form.sessionDurationMinutes,
    sessionPacks: form.sessionPacks.filter(p => p.sessionCount > 0 && p.totalPrice > 0).map(p => ({
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
