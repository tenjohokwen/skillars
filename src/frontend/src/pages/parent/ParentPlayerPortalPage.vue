<template>
  <q-page class="q-pa-md">
    <TimezoneNotice
      v-if="firstBookingTimezone && !authStore.timezoneNoticeDismissed"
      :pitch-timezone="firstBookingTimezone"
    />

    <!-- Child hub (navigation reachability): one place linking to every per-child destination a
         parent has. The session list below is the hub's own content. -->
    <div class="glass-card q-pa-md q-mb-md" data-testid="child-hub-header">
      <div class="text-label">{{ t('parent.hub.title') }}</div>
      <div v-if="childName" class="text-h6" data-testid="child-hub-name">{{ childName }}</div>
      <div class="row q-gutter-sm q-mt-sm">
        <q-btn
          v-bind="hubLinkProps"
          icon="insights"
          :to="links.development"
          :label="t('parent.hub.development')"
        />
        <q-btn
          v-bind="hubLinkProps"
          icon="confirmation_number"
          :to="links.packs"
          :label="t('parent.hub.packs')"
        />
        <q-btn
          v-bind="hubLinkProps"
          icon="workspace_premium"
          :to="links.subscription"
          :label="t('parent.hub.subscription')"
        />
        <q-btn
          v-bind="hubLinkProps"
          icon="sports_soccer"
          :to="links.lockerRoom"
          :label="t('parent.hub.lockerRoom')"
        />
        <q-btn
          v-bind="hubLinkProps"
          icon="task_alt"
          to="/parent/approvals"
          :label="t('parent.hub.approvals')"
        />
      </div>
    </div>

    <div class="text-h5 q-mb-md">{{ t('booking.timezone.parentScheduleTitle') }}</div>

    <div v-if="bookingStore.parentScheduleLoading" class="flex flex-center q-py-xl">
      <q-spinner size="48px" />
    </div>

    <!-- A failed load must not fall through to the list: parentSchedule may still hold the
         previously viewed child's sessions. -->
    <div
      v-else-if="bookingStore.parentScheduleError"
      class="text-body2 text-negative q-py-md"
      data-testid="child-hub-schedule-error"
    >
      {{ t('common.errorGeneric') }}
    </div>

    <div
      v-else-if="!bookingStore.parentSchedule || bookingStore.parentSchedule.sessions.length === 0"
      class="flex flex-center column q-gutter-md q-py-xl"
      style="min-height: 40vh"
    >
      <q-icon name="event_busy" size="64px" style="color: var(--text-secondary)" />
      <div class="text-body1" style="color: var(--text-secondary)">
        {{ t('booking.timezone.parentScheduleEmpty') }}
      </div>
    </div>

    <q-list v-else bordered separator>
      <q-item
        v-for="session in bookingStore.parentSchedule.sessions"
        :key="session.bookingId"
        class="q-py-md"
      >
        <q-item-section>
          <q-item-label class="text-weight-bold">{{ session.coachDisplayName }}</q-item-label>
          <q-item-label caption>
            {{ formatInTz(session.requestedStartTime, session.canonicalTimezone) }}
          </q-item-label>
          <div class="q-mt-sm">
            <SessionPackTracker
              :credits-remaining="creditsRemainingFor(session)"
              :session-count="sessionCountFor(session)"
            />
          </div>
        </q-item-section>
        <q-item-section side>
          <BookingStateChip :status="session.status" />
        </q-item-section>
      </q-item>
    </q-list>
  </q-page>
</template>

<script setup>
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'
import { useAuthStore } from 'src/stores/auth.store'
import { usePlayerStore } from 'src/stores/playerStore'
import BookingStateChip from 'src/components/booking/BookingStateChip.vue'
import SessionPackTracker from 'src/components/booking/SessionPackTracker.vue'
import TimezoneNotice from 'src/components/booking/TimezoneNotice.vue'
import { parentChildLinks } from 'src/utils/parentChildLinks'

const { t, locale } = useI18n()
const route = useRoute()
const bookingStore = useBookingStore()
const authStore = useAuthStore()
const playerStore = usePlayerStore()

// Kept as the raw route-param STRING (never Number(...)): player ids are Tsids past
// Number.MAX_SAFE_INTEGER (skillars-deferred-148 AC1). Computed so a param-only navigation (the
// "My players" drawer entries and ParentChildSwitcher reuse this component across children)
// reloads for the new child instead of keeping the first child's data under the new URL.
const playerId = computed(() => route.params.playerId)
const links = computed(() => parentChildLinks(playerId.value))
const childName = computed(
  () => playerStore.players.find((p) => String(p.id) === String(playerId.value))?.name ?? null,
)

const hubLinkProps = { outline: true, rounded: true, noCaps: true, color: 'primary' }

// Loads are SERIALIZED: the store's loaders write shared refs (parentSchedule, sessionPacks) with no
// per-player guard, so two overlapping loads (child A still in flight when the parent switches to
// child B) could resolve out of order and leave A's sessions under B's header. Chaining makes the
// last-requested child's load always the last write; a queued load whose child is no longer the
// one on screen is skipped. The store loaders never reject (they catch into their *Error refs).
let loadChain = Promise.resolve()

function loadForPlayer(id) {
  if (!id) return loadChain
  // Keep the header ParentChildSwitcher's highlighted child in step with the child on screen.
  playerStore.setActivePlayer(id)
  loadChain = loadChain.then(async () => {
    if (id !== playerId.value) return
    await bookingStore.loadParentSchedule(id)
    await bookingStore.loadPlayerPacks(id)
  })
  return loadChain
}

onMounted(() => loadForPlayer(playerId.value))

// Previously this page watched playerStore.activePlayerId and reloaded THAT child's data while the
// URL still named the original child. The route param is now the single source of truth: the
// switcher navigates here (see ParentChildSwitcher.vue) instead of mutating state underneath.
watch(
  () => route.params.playerId,
  (newId, oldId) => {
    if (newId && newId !== oldId) loadForPlayer(newId)
  },
)

const firstBookingTimezone = computed(() => {
  return bookingStore.parentSchedule?.sessions?.[0]?.canonicalTimezone ?? null
})

function formatInTz(isoString, timezone) {
  return new Intl.DateTimeFormat(locale.value, {
    timeZone: timezone,
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(isoString))
}

// A player+coach pair can now have multiple simultaneously-active packs (unlike legacy's
// single-active-pack model) — pick the soonest-expiring one as "the" pack to display here,
// since that's the one whose credits run out first and most needs the parent's attention.
function mostRelevantPackFor(session) {
  const activePacks = bookingStore.sessionPacks.filter(
    (p) => p.coachId === session.coachId && p.status === 'ACTIVE',
  )
  if (activePacks.length === 0) return null
  return activePacks.reduce((soonest, p) =>
    new Date(p.expiresAt) < new Date(soonest.expiresAt) ? p : soonest,
  )
}

function sessionCountFor(session) {
  const pack = mostRelevantPackFor(session)
  return pack ? pack.sessionCount : 0
}

function creditsRemainingFor(session) {
  const pack = mostRelevantPackFor(session)
  return pack ? pack.creditsRemaining : 0
}
</script>
