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
    <!-- skillars-deferred-140 AC1.1 (Option B): read-only — saveStep4 now always overwrites every
         window's zone with the profile's own (Step 1) zone, so offering an independent picker here
         implied a choice the backend discards. Mirrors EditCoachAvailabilityDialog.vue's identical
         read-only display, fetched from the same backend source of truth rather than the
         session-only Pinia store, which is empty for a coach resuming the builder in a fresh
         session.

         skillars-deferred-140 code review: the picker comes back as a RECOVERY path only. Read-only
         with no fallback dead-ended the step — `:disable="!canonicalTimezone"` below left Next
         permanently disabled, with the mount failure silently swallowed and no control to supply a
         value, whenever the profile fetch failed on a fresh session (store empty). The same dead end
         was reachable with a perfectly successful fetch: a coach whose stored zone predates the
         2026-08-25 @IanaTimezone tightening (a fixed offset like "+01:00") would have had that value
         stamped onto every window and 400'd on submit, again with no way to change it. -->
    <template v-if="needsManualTimezone">
      <q-banner class="timezone-recovery q-mb-sm" rounded dense>
        <template #avatar>
          <q-icon name="error_outline" />
        </template>
        {{ t('auth.coach.step4TimezoneUnavailable') }}
      </q-banner>
      <TimezoneSelect v-model="canonicalTimezone" />
    </template>
    <template v-else>
      <div class="text-body q-mb-xs">{{ canonicalTimezone || '—' }}</div>
      <div class="text-meta q-mb-sm">{{ t('auth.coach.step4TimezoneReadonlyHint') }}</div>
    </template>

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
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProfileBuilderStore } from 'src/stores/profileBuilder.store'
import { getOwnCoachProfile } from 'src/api/marketplace.api'
import TimezoneSelect from 'src/components/profileBuilder/TimezoneSelect.vue'

const { t } = useI18n()
const store = useProfileBuilderStore()

defineProps({ loading: Boolean })
const emit = defineEmits(['submit'])

// skillars-deferred-140 AC1.1 (Option B): display-only now — saveStep4 always overwrites every
// window's zone with the coach's profile zone, so this is no longer a choice the coach makes here.
// Defaults to the session-only store value (set by Step 1, immediate), then confirmed/corrected
// from the backend on mount — the authoritative source, and the only one that still has a value
// for a coach resuming the builder in a fresh session (the Pinia store resets on reload).
const canonicalTimezone = ref(store.selectedTimezone ?? null)

// Gates needsManualTimezone so the recovery picker never flashes while the mount requests are still
// in flight — without it, the first render (store empty, fetch unresolved) would look identical to a
// genuine failure.
const timezoneResolved = ref(false)

onMounted(async () => {
  // The supported-zone list is what lets us tell a usable stored zone from a legacy one the server
  // would now reject on submit. Cached per session by the store, and already warmed by Step 1 in the
  // normal flow, so this is usually free.
  await store.loadSupportedTimezones()
  try {
    const profile = await getOwnCoachProfile()
    if (profile?.canonicalTimezone) {
      canonicalTimezone.value = profile.canonicalTimezone
    }
  } catch {
    // Step 1 has already run by the time a coach reaches Step 4, so a profile should always exist.
    // On failure we fall through to needsManualTimezone, which surfaces the picker rather than
    // leaving the coach at a disabled Next button with nothing to act on.
  } finally {
    timezoneResolved.value = true
  }
})

/**
 * True when the coach must pick a zone themselves, because we have none or the one we have is not
 * something this server will accept.
 *
 * When the supported list itself failed to load we cannot judge a stored zone, so an existing value
 * is trusted rather than second-guessed — TimezoneSelect owns the list-failure banner and retry for
 * the case where there is no value either.
 */
const needsManualTimezone = computed(() => {
  if (!timezoneResolved.value) return false
  if (!canonicalTimezone.value) return true
  const supported = store.supportedTimezones
  return supported.length > 0 && !supported.includes(canonicalTimezone.value)
})

// skillars-deferred-92 code review, chunk 3: was a hardcoded English array — invisible to AC14's
// sweep because it lives in <script>, not a template text node, so a French coach saw English
// weekday names next to the (translated) step4Day label.
const dayOptions = computed(() => [
  { label: t('auth.coach.weekdayMonday'), value: 1 },
  { label: t('auth.coach.weekdayTuesday'), value: 2 },
  { label: t('auth.coach.weekdayWednesday'), value: 3 },
  { label: t('auth.coach.weekdayThursday'), value: 4 },
  { label: t('auth.coach.weekdayFriday'), value: 5 },
  { label: t('auth.coach.weekdaySaturday'), value: 6 },
  { label: t('auth.coach.weekdaySunday'), value: 7 },
])

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
  // canonicalTimezone joins the guard for the same reason as Step 1: the request schema still
  // requires @NotBlank/@IanaTimezone per window even though the backend discards and overwrites it.
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

/* Matches TimezoneSelect.vue's own .timezone-hint banner styling, since this banner sits directly
   above that component in the recovery state. */
.timezone-recovery {
  background: var(--surface-warning) !important;
  color: var(--accent-warning) !important;
  border-radius: 8px !important;
  font-size: 13px;
}
</style>
