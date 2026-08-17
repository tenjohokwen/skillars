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
    <div class="text-meta q-mb-sm">{{ t('auth.coach.step4TimezoneHelper') }}</div>
    <TimezoneSelect v-model="canonicalTimezone" />

    <div class="q-mt-lg">
      <q-btn
        :label="t('common.next')"
        class="btn-accent"
        @click="submit"
        :loading="loading"
        :disable="!canonicalTimezone"
        unelevated
      />
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProfileBuilderStore } from 'src/stores/profileBuilder.store'
import TimezoneSelect from './TimezoneSelect.vue'

const { t } = useI18n()
const store = useProfileBuilderStore()

defineProps({ loading: Boolean })
const emit = defineEmits(['submit'])

// Defaults to whatever Step 1 chose, so the two canonical_timezone columns agree for a coach who
// walks the builder in one sitting. When the store is empty — a coach resuming in a fresh session —
// TimezoneSelect falls back to preselecting the browser zone if the server recognises it.
// NOTE: this reduces divergence for NEW coaches; it does not reconcile the two columns or backfill
// existing rows (deferred-17 D8 remains open, and is deliberately out of scope here).
//
// Read ONCE at setup, deliberately — this must not re-sync from the store afterwards. The host page
// renders the five steps through a v-if/v-else-if chain of distinct components, so navigating away
// from Step 4 unmounts it and returning re-runs this setup with the current store value; there is no
// window in which Step 1 can change the zone while Step 4 is mounted. Making this a computed or
// adding a watcher would therefore fix nothing and break something real: it would silently overwrite
// a per-window zone the coach had deliberately chosen here.
const canonicalTimezone = ref(store.selectedTimezone ?? null)

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
  const valid =
    form.windows.length > 0 && form.windows.every((w) => w.dayOfWeek && w.startTime && w.endTime)
  // canonicalTimezone joins the guard for the same reason as Step 1: @NotBlank would 400, and the
  // picker exists so the coach always holds a value the server will accept.
  if (!valid || !canonicalTimezone.value) return
  store.setSelectedTimezone(canonicalTimezone.value)
  emit('submit', {
    windows: form.windows.map((w) => ({
      dayOfWeek: w.dayOfWeek,
      startTime: w.startTime,
      endTime: w.endTime,
      canonicalTimezone: canonicalTimezone.value,
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
