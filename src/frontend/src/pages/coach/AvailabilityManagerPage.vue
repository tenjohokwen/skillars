<template>
  <q-page class="availability-page">
    <div class="availability-page__container fade-in">
      <div class="availability-page__header">
        <div class="gradient-text text-section-title">{{ t('booking.availability.title') }}</div>

        <div class="availability-page__week-nav q-mt-sm">
          <q-btn flat dense icon="chevron_left" @click="prevWeek" />
          <span class="week-label">{{ weekLabel }}</span>
          <q-btn flat dense icon="chevron_right" @click="nextWeek" />
        </div>
      </div>

      <div v-if="store.loading" class="q-py-xl text-center">
        <q-spinner size="48px" color="primary" />
      </div>

      <template v-else>
        <WeeklyCalendar
          v-if="coachId"
          :week-start="currentWeekStart"
          :windows="store.windows"
          :blocks="store.blocks"
          :coach-timezone="coachTimezone"
          @edit-window="onEditWindow"
          @delete-window="onDeleteWindow"
          @delete-block="onDeleteBlock"
          @add-window="onAddWindowForDay"
        />

        <!-- Inline add/edit panel — always visible so coach never loses calendar context -->
        <div class="glass-card--static availability-page__panel q-mt-lg">
          <div class="text-subtitle2 q-mb-md">
            {{ editingWindow ? t('booking.availability.editWindow') : t('booking.availability.addWindow') }}
          </div>

          <div class="row q-col-gutter-sm">
            <div class="col-12 col-sm-3">
              <q-select
                v-model="form.dayOfWeek"
                outlined dense
                :label="t('common.day')"
                :options="dayOptions"
                emit-value map-options
              />
            </div>
            <div class="col-12 col-sm-3">
              <q-input
                v-model="form.startTime"
                outlined dense
                type="time"
                :label="t('common.startTime')"
              />
            </div>
            <div class="col-12 col-sm-3">
              <q-input
                v-model="form.endTime"
                outlined dense
                type="time"
                :label="t('common.endTime')"
              />
            </div>
            <div class="col-12 col-sm-3 flex items-end q-gutter-xs">
              <q-btn unelevated color="primary" :label="t('common.save')"
                     :loading="saving" @click="onSaveWindow" />
              <q-btn v-if="editingWindow" flat :label="t('common.cancel')"
                     @click="cancelEdit" />
            </div>
          </div>

          <q-banner v-if="windowSaved" rounded class="q-mt-sm bg-positive text-white">
            {{ t('booking.availability.windowAdded') }}
          </q-banner>
          <q-banner v-if="saveError" rounded class="q-mt-sm bg-negative text-white">
            {{ saveError }}
          </q-banner>
        </div>

        <!-- Add manual block panel -->
        <div class="glass-card--static availability-page__panel q-mt-md">
          <div class="text-subtitle2 q-mb-md">{{ t('booking.availability.addBlock') }}</div>

          <div class="row q-col-gutter-sm">
            <div class="col-12 col-sm-3">
              <q-input
                v-model="blockForm.startDate"
                outlined dense
                type="date"
                :label="t('common.startDate')"
              />
            </div>
            <div class="col-12 col-sm-3">
              <q-input
                v-model="blockForm.startTime"
                outlined dense
                type="time"
                :label="t('common.startTime')"
              />
            </div>
            <div class="col-12 col-sm-3">
              <q-input
                v-model="blockForm.endDate"
                outlined dense
                type="date"
                :label="t('common.endDate')"
              />
            </div>
            <div class="col-12 col-sm-3">
              <q-input
                v-model="blockForm.endTime"
                outlined dense
                type="time"
                :label="t('common.endTime')"
              />
            </div>
            <div class="col-12">
              <q-input
                v-model="blockForm.reason"
                outlined dense
                :label="t('common.reason') + ' (' + t('common.optional') + ')'"
              />
            </div>
            <div class="col-12">
              <q-btn unelevated color="secondary" :label="t('booking.availability.addBlock')"
                     :loading="savingBlock" @click="onSaveBlock" />
            </div>
          </div>

          <q-banner v-if="blockSaved" rounded class="q-mt-sm bg-positive text-white">
            {{ t('booking.availability.blockAdded') }}
          </q-banner>
          <q-banner v-if="blockSaveError" rounded class="q-mt-sm bg-negative text-white">
            {{ blockSaveError }}
          </q-banner>
        </div>
      </template>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'
import { getProfileBuilderStatus } from 'src/api/marketplace.api'
import WeeklyCalendar from 'src/components/availability/WeeklyCalendar.vue'

const { t } = useI18n()
const store = useBookingStore()

const coachId = ref(null)
const coachTimezone = ref('UTC')

const editingWindow = ref(null)
const saving = ref(false)
const windowSaved = ref(false)
const savingBlock = ref(false)
const blockSaved = ref(false)

const form = ref({ dayOfWeek: 1, startTime: '09:00', endTime: '11:00' })
const blockForm = ref({
  startDate: '', startTime: '00:00',
  endDate: '', endTime: '23:59',
  reason: '',
})

function mondayOf(dateStr) {
  const d = new Date(dateStr + 'T00:00:00')
  const day = d.getDay()
  const diff = day === 0 ? -6 : 1 - day
  d.setDate(d.getDate() + diff)
  return d.toISOString().slice(0, 10)
}

const today = new Date().toISOString().slice(0, 10)
const currentWeekStart = ref(mondayOf(today))

const weekLabel = computed(() => {
  const start = new Date(currentWeekStart.value + 'T00:00:00')
  const end = new Date(start)
  end.setDate(start.getDate() + 6)
  const fmt = (d) => new Intl.DateTimeFormat('en', { month: 'short', day: 'numeric' }).format(d)
  return `${fmt(start)} – ${fmt(end)}`
})

function prevWeek() {
  const d = new Date(currentWeekStart.value + 'T00:00:00')
  d.setDate(d.getDate() - 7)
  currentWeekStart.value = d.toISOString().slice(0, 10)
  if (coachId.value) store.loadAvailability(coachId.value, currentWeekStart.value)
}

function nextWeek() {
  const d = new Date(currentWeekStart.value + 'T00:00:00')
  d.setDate(d.getDate() + 7)
  currentWeekStart.value = d.toISOString().slice(0, 10)
  if (coachId.value) store.loadAvailability(coachId.value, currentWeekStart.value)
}

const dayOptions = [
  { label: 'Monday', value: 1 },
  { label: 'Tuesday', value: 2 },
  { label: 'Wednesday', value: 3 },
  { label: 'Thursday', value: 4 },
  { label: 'Friday', value: 5 },
  { label: 'Saturday', value: 6 },
  { label: 'Sunday', value: 7 },
]

function onEditWindow(win) {
  editingWindow.value = win
  form.value = {
    dayOfWeek: win.dayOfWeek,
    startTime: win.startTime?.slice(0, 5) ?? '09:00',
    endTime: win.endTime?.slice(0, 5) ?? '11:00',
  }
}

function cancelEdit() {
  editingWindow.value = null
  form.value = { dayOfWeek: 1, startTime: '09:00', endTime: '11:00' }
}

function onAddWindowForDay(day) {
  editingWindow.value = null
  form.value = { dayOfWeek: day.isoDay, startTime: '09:00', endTime: '11:00' }
}

const saveError = ref(null)

async function onSaveWindow() {
  saving.value = true
  windowSaved.value = false
  saveError.value = null
  try {
    const payload = {
      dayOfWeek: form.value.dayOfWeek,
      startTime: form.value.startTime + ':00',
      endTime: form.value.endTime + ':00',
    }
    if (editingWindow.value) {
      await store.editWindow(coachId.value, editingWindow.value.id, payload)
    } else {
      await store.createWindow(coachId.value, payload)
    }
    cancelEdit()
    windowSaved.value = true
    setTimeout(() => { windowSaved.value = false }, 3000)
  } catch (e) {
    saveError.value = e?.response?.data?.message ?? e?.message ?? 'Failed to save window'
  } finally {
    saving.value = false
  }
}

async function onDeleteWindow(id) {
  if (!confirm(t('booking.availability.deleteConfirm'))) return
  await store.removeWindow(coachId.value, id)
}

async function onDeleteBlock(id) {
  await store.removeBlock(coachId.value, id)
}

const blockSaveError = ref(null)

function localDateTimeToUtc(dateStr, timeStr, tz) {
  // Parse the date+time as a wall-clock time in the coach's canonical timezone,
  // then convert to UTC ISO string. Uses Intl to determine the UTC offset at that instant.
  const naiveIso = `${dateStr}T${timeStr}:00`
  // Find the UTC offset for this wall-clock time in the target timezone
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: tz,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    hour12: false,
  })
  // Determine offset by comparing UTC date parts to localized date parts
  const nowUtc = new Date(naiveIso + 'Z')
  const localParts = formatter.formatToParts(nowUtc)
  const get = (type) => Number(localParts.find(p => p.type === type)?.value ?? 0)
  const localDate = new Date(Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'), get('second')))
  const offsetMs = nowUtc - localDate
  return new Date(new Date(naiveIso + 'Z').getTime() + offsetMs).toISOString()
}

async function onSaveBlock() {
  if (!blockForm.value.startDate || !blockForm.value.endDate) return
  blockSaveError.value = null
  const tz = coachTimezone.value || 'UTC'
  const startDatetime = localDateTimeToUtc(blockForm.value.startDate, blockForm.value.startTime, tz)
  const endDatetime = localDateTimeToUtc(blockForm.value.endDate, blockForm.value.endTime, tz)
  if (isNaN(new Date(startDatetime)) || isNaN(new Date(endDatetime))) {
    blockSaveError.value = 'Invalid date or time entered'
    return
  }
  if (new Date(endDatetime) <= new Date(startDatetime)) {
    blockSaveError.value = 'End must be after start'
    return
  }
  savingBlock.value = true
  blockSaved.value = false
  try {
    await store.createBlock(coachId.value, {
      startDatetime,
      endDatetime,
      reason: blockForm.value.reason || null,
    })
    blockForm.value = { startDate: '', startTime: '00:00', endDate: '', endTime: '23:59', reason: '' }
    blockSaved.value = true
    setTimeout(() => { blockSaved.value = false }, 3000)
  } catch (e) {
    blockSaveError.value = e?.response?.data?.message ?? e?.message ?? 'Failed to save block'
  } finally {
    savingBlock.value = false
  }
}

onMounted(async () => {
  try {
    const res = await getProfileBuilderStatus()
    coachId.value = res.data?.coachId ?? null
    if (!coachId.value) {
      store.error = new Error('No coach profile found for this account')
      return
    }
  } catch (e) {
    store.error = e
    return
  }
  await store.loadAvailability(coachId.value, currentWeekStart.value)
  if (store.windows.length > 0) {
    coachTimezone.value = store.windows[0].canonicalTimezone ?? 'UTC'
  }
})
</script>

<style lang="scss" scoped>
.availability-page {
  padding: 24px 16px;
  max-width: 1100px;
  margin: 0 auto;
}

.availability-page__container { width: 100%; }

.availability-page__header {
  margin-bottom: 24px;
}

.availability-page__week-nav {
  display: flex;
  align-items: center;
  gap: 8px;
}

.week-label {
  font-size: 0.95rem;
  font-weight: 600;
  min-width: 180px;
  text-align: center;
}

.availability-page__panel {
  padding: 20px 24px;
}
</style>
