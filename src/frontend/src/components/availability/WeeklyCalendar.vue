<template>
  <div class="weekly-calendar">
    <!-- Header row: time label + Mon–Sun -->
    <div class="cal-header">
      <div class="time-col-label"></div>
      <div
        v-for="(day, idx) in weekDays"
        :key="idx"
        class="day-col-header"
      >
        <div class="day-name">{{ day.label }}</div>
        <div class="day-date">{{ day.date }}</div>
      </div>
    </div>

    <!-- Calendar body: time slots x 7 days -->
    <div class="cal-body">
      <div class="time-col">
        <div
          v-for="slot in timeSlots"
          :key="slot"
          class="time-label"
        >{{ slot }}</div>
      </div>

      <div
        v-for="(day, dayIdx) in weekDays"
        :key="dayIdx"
        class="day-col"
        @click="onDayClick(day)"
      >
        <div
          v-for="slot in timeSlots"
          :key="slot"
          class="time-cell"
          :class="getCellClass()"
        ></div>

        <!-- Availability window overlays -->
        <div
          v-for="win in windowsForDay(day.isoDay)"
          :key="win.id"
          class="window-overlay"
          :style="getWindowStyle(win)"
          @click.stop="onEditWindow(win)"
        >
          <span class="overlay-label">{{ formatTime(win.startTime) }}–{{ formatTime(win.endTime) }}</span>
          <button class="overlay-delete" @click.stop="$emit('delete-window', win.id)">✕</button>
        </div>

        <!-- Block overlays -->
        <div
          v-for="blk in blocksForDay(day.fullDate)"
          :key="blk.id"
          class="block-overlay"
          :style="getBlockStyle(blk)"
          @click.stop
        >
          <span class="overlay-label">{{ $t('booking.availability.blocked') }}</span>
          <button class="overlay-delete" @click.stop="$emit('delete-block', blk.id)">✕</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  weekStart: { type: String, required: true },
  windows: { type: Array, default: () => [] },
  blocks: { type: Array, default: () => [] },
  coachTimezone: { type: String, default: 'UTC' },
})

const emit = defineEmits(['edit-window', 'delete-window', 'delete-block', 'add-window'])

const DAY_START_HOUR = 6
const DAY_END_HOUR = 22

const timeSlots = computed(() => {
  const slots = []
  for (let h = DAY_START_HOUR; h <= DAY_END_HOUR; h++) {
    slots.push(`${String(h).padStart(2, '0')}:00`)
  }
  return slots
})

const weekDays = computed(() => {
  const days = []
  const dayNames = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
  const base = new Date(props.weekStart + 'T00:00:00')
  if (isNaN(base.getTime())) return days
  for (let i = 0; i < 7; i++) {
    const d = new Date(props.weekStart + 'T00:00:00')
    d.setDate(d.getDate() + i)
    const date = new Intl.DateTimeFormat('en', {
      month: 'short', day: 'numeric',
      timeZone: props.coachTimezone,
    }).format(d)
    days.push({
      isoDay: i + 1,
      label: dayNames[i],
      date,
      fullDate: d.toISOString().slice(0, 10),
    })
  }
  return days
})

function windowsForDay(isoDay) {
  return props.windows.filter(w => w.dayOfWeek === isoDay)
}

function blocksForDay(dayDate) {
  return props.blocks.filter(b => {
    if (!b.startDatetime || !b.endDatetime) return false
    const startDt = new Date(b.startDatetime)
    const endDt = new Date(b.endDatetime)
    if (isNaN(startDt.getTime()) || isNaN(endDt.getTime())) return false
    const start = new Intl.DateTimeFormat('en-CA', {
      timeZone: props.coachTimezone,
      year: 'numeric', month: '2-digit', day: '2-digit',
    }).format(startDt)
    const end = new Intl.DateTimeFormat('en-CA', {
      timeZone: props.coachTimezone,
      year: 'numeric', month: '2-digit', day: '2-digit',
    }).format(endDt)
    return start <= dayDate && end >= dayDate
  })
}

function getCellClass() {
  return {}
}

function timeToMinutes(timeStr) {
  const [h, m] = timeStr.split(':').map(Number)
  return h * 60 + m
}

function minutesToTopPercent(minutes) {
  const totalMinutes = (DAY_END_HOUR - DAY_START_HOUR) * 60
  const offsetMinutes = minutes - DAY_START_HOUR * 60
  return Math.max(0, Math.min(100, (offsetMinutes / totalMinutes) * 100))
}

function minutesToHeightPercent(durationMinutes) {
  const totalMinutes = (DAY_END_HOUR - DAY_START_HOUR) * 60
  return Math.max(0, Math.min(100, (durationMinutes / totalMinutes) * 100))
}

function getWindowStyle(win) {
  if (!win.startTime || !win.endTime) return { top: '0%', height: '0%' }
  const startMin = timeToMinutes(win.startTime)
  const endMin = timeToMinutes(win.endTime)
  if (isNaN(startMin) || isNaN(endMin) || endMin <= startMin) return { top: '0%', height: '0%' }
  return {
    top: `${minutesToTopPercent(startMin)}%`,
    height: `${minutesToHeightPercent(endMin - startMin)}%`,
  }
}

function getBlockStyle(blk) {
  const tz = props.coachTimezone
  const startDt = new Date(blk.startDatetime)
  const endDt = new Date(blk.endDatetime)

  const fmt = (dt, field) => Number(new Intl.DateTimeFormat('en', {
    [field]: 'numeric', hour12: false, timeZone: tz,
  }).format(dt))

  const startH = fmt(startDt, 'hour')
  const startM = fmt(startDt, 'minute')
  const endH = fmt(endDt, 'hour')
  const endM = fmt(endDt, 'minute')

  const startMin = Math.max(startH * 60 + startM, DAY_START_HOUR * 60)
  const endMin = Math.min(endH * 60 + endM, DAY_END_HOUR * 60)

  if (endMin <= startMin) return { top: '0%', height: '0%' }

  return {
    top: `${minutesToTopPercent(startMin)}%`,
    height: `${minutesToHeightPercent(endMin - startMin)}%`,
  }
}

function formatTime(timeStr) {
  if (!timeStr) return ''
  return timeStr.slice(0, 5)
}

function onEditWindow(win) {
  emit('edit-window', win)
}

function onDayClick(day) {
  emit('add-window', day)
}
</script>

<style scoped>
.weekly-calendar {
  display: flex;
  flex-direction: column;
  width: 100%;
  border: 1px solid var(--glass-border, rgba(255,255,255,0.15));
  border-radius: 8px;
  overflow: hidden;
}

.cal-header {
  display: grid;
  grid-template-columns: 60px repeat(7, 1fr);
  border-bottom: 1px solid var(--glass-border, rgba(255,255,255,0.15));
}

.time-col-label {
  width: 60px;
}

.day-col-header {
  padding: 8px 4px;
  text-align: center;
  font-size: 0.8rem;
  font-weight: 600;
}

.day-name { opacity: 0.7; }
.day-date { font-size: 0.75rem; }

.cal-body {
  display: grid;
  grid-template-columns: 60px repeat(7, 1fr);
  position: relative;
  min-height: 400px;
}

.time-col {
  display: flex;
  flex-direction: column;
}

.time-label {
  height: 48px;
  font-size: 0.7rem;
  padding: 2px 4px;
  opacity: 0.6;
  border-top: 1px solid var(--glass-border, rgba(255,255,255,0.08));
}

.day-col {
  position: relative;
  border-left: 1px solid var(--glass-border, rgba(255,255,255,0.08));
  cursor: pointer;
}

.time-cell {
  height: 48px;
  border-top: 1px solid var(--glass-border, rgba(255,255,255,0.08));
}

.window-overlay,
.block-overlay {
  position: absolute;
  left: 2px;
  right: 2px;
  border-radius: 4px;
  padding: 2px 4px;
  font-size: 0.7rem;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  overflow: hidden;
  cursor: pointer;
}

.window-overlay {
  background: rgba(34, 197, 94, 0.35);
  border: 1px solid rgba(34, 197, 94, 0.6);
}

.block-overlay {
  background: rgba(107, 114, 128, 0.45);
  border: 1px solid rgba(107, 114, 128, 0.6);
  cursor: default;
}

.overlay-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.overlay-delete {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 0.65rem;
  opacity: 0.7;
  padding: 0 2px;
  line-height: 1;
  flex-shrink: 0;
}

.overlay-delete:hover { opacity: 1; }
</style>
