<template>
  <div>
    <div class="text-caption q-mb-xs">{{ t('auth.coach.step4AvailabilityWindows') }}</div>
    <div v-for="(win, i) in form.windows" :key="i" class="row q-col-gutter-sm q-mb-sm items-center">
      <div class="col-3">
        <q-select
          v-model="win.dayOfWeek"
          :options="dayOptions"
          option-value="value"
          option-label="label"
          emit-value
          map-options
          label="Day"
          outlined
          dense
        />
      </div>
      <div class="col-3">
        <q-input v-model="win.startTime" label="Start" outlined dense type="time" />
      </div>
      <div class="col-3">
        <q-input v-model="win.endTime" label="End" outlined dense type="time" />
      </div>
      <div class="col-2">
        <q-btn icon="close" flat dense @click="removeWindow(i)" />
      </div>
    </div>
    <q-btn
      :label="t('auth.coach.step4AddWindow')"
      flat
      size="sm"
      icon="add"
      @click="addWindow"
      class="q-mb-md"
    />
    <div>
      <q-btn
        :label="t('common.next')"
        color="primary"
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
