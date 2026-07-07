<template>
  <div>
    <div class="text-label q-mb-xs">{{ t('auth.coach.step4AvailabilityWindows') }}</div>
    <div class="text-meta q-mb-sm">{{ t('auth.coach.step4WindowHelper') }}</div>

    <div v-if="!form.windows.length" class="profile-builder__empty-state q-mb-sm">
      {{ t('auth.coach.step4WindowEmpty') }}
    </div>

    <div v-for="(win, i) in form.windows" :key="i" class="profile-builder__entry-card q-mb-sm">
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
          <q-input v-model="win.startTime" :label="t('auth.coach.step4Start')" outlined dense type="time" />
        </div>
        <div class="col-5 col-sm-3">
          <q-input v-model="win.endTime" :label="t('auth.coach.step4End')" outlined dense type="time" />
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
import { reactive } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({ loading: Boolean })
const emit = defineEmits(['submit'])

const canonicalTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone

const dayOptions = [
  { label: 'Monday', value: 1 },
  { label: 'Tuesday', value: 2 },
  { label: 'Wednesday', value: 3 },
  { label: 'Thursday', value: 4 },
  { label: 'Friday', value: 5 },
  { label: 'Saturday', value: 6 },
  { label: 'Sunday', value: 7 },
]

const form = reactive({ windows: [] })

function addWindow() {
  form.windows.push({ dayOfWeek: null, startTime: '', endTime: '' })
}

function removeWindow(i) {
  form.windows.splice(i, 1)
}

function submit() {
  const valid = form.windows.length > 0 && form.windows.every(w => w.dayOfWeek && w.startTime && w.endTime)
  if (!valid) return
  emit('submit', {
    windows: form.windows.map(w => ({
      dayOfWeek: w.dayOfWeek,
      startTime: w.startTime,
      endTime: w.endTime,
      canonicalTimezone,
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
  padding: 12px;
  background: var(--surface-glass);
  border: 1px solid var(--border-soft);
  border-radius: 14px;
}
</style>
