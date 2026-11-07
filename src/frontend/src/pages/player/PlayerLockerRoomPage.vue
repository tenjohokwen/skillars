<template>
  <q-page class="q-pa-md" data-testid="locker-room">
    <div class="row items-center q-mb-lg">
      <div class="gradient-text text-h5 q-mr-sm">{{ t('player.lockerRoomTitle') }}</div>
    </div>

    <div v-if="homeworkStore.loading" class="flex flex-center q-pa-xl">
      <q-spinner-dots size="36px" color="primary" />
    </div>

    <!-- A failed load (a 403 included) is not "no homework yet": say it failed. -->
    <q-banner
      v-else-if="homeworkStore.error"
      rounded
      class="bg-negative text-white"
      data-testid="locker-room-error"
    >
      {{ t('common.errorGeneric') }}
    </q-banner>

    <div
      v-else-if="!homeworkStore.assignments.length"
      class="locker-room__empty text-center q-pa-xl"
      data-testid="locker-room-empty"
    >
      <q-icon name="sports_soccer" size="64px" color="grey-5" />
      <div class="text-h6 q-mt-md">{{ t('player.homeworkEmptyTitle') }}</div>
      <div class="text-body2 text-secondary q-mt-sm">{{ t('player.homeworkEmptySubtitle') }}</div>
    </div>

    <template v-else>
      <div
        v-for="(group, coachId) in groupedByCoach"
        :key="coachId"
        class="q-mb-xl"
        data-testid="locker-room-group"
        :data-coach-id="coachId"
      >
        <div
          v-if="Object.keys(groupedByCoach).length > 1"
          class="text-subtitle2 q-mb-sm text-secondary"
          data-testid="locker-room-group-header"
        >
          {{ t('player.assignedBy', { coach: group[0].coachDisplayName }) }}
        </div>
        <div class="locker-room__drills">
          <div
            v-for="item in group"
            :key="item.assignmentId"
            class="locker-room__drill-wrapper q-mb-md"
            data-testid="locker-room-assignment"
            :data-assignment-id="item.assignmentId"
            :data-completed="item.completed"
          >
            <DrillCard
              :drill="item.drill"
              context="locker-room"
              class="full-width"
              @video-error="handleVideoError"
            />
            <div class="locker-room__completion-row row items-center q-mt-xs q-px-sm">
              <q-checkbox
                :model-value="item.completed"
                :label="
                  item.completed ? t('player.homeworkCompleted') : t('player.homeworkMarkDone')
                "
                :color="item.completed ? 'positive' : 'primary'"
                :disable="item.completed"
                data-testid="homework-done"
                @update:model-value="() => handleMarkComplete(item.assignmentId)"
              />
            </div>
          </div>
        </div>
      </div>
    </template>
  </q-page>
</template>

<script setup>
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useHomeworkStore } from 'src/stores/homework.store'
import DrillCard from 'src/components/session/DrillCard.vue'

defineOptions({ name: 'LockerRoomPage' })

const { t } = useI18n()
const $q = useQuasar()
const route = useRoute()
const homeworkStore = useHomeworkStore()

const playerId = computed(() => route.params.playerId)

const groupedByCoach = computed(() => {
  return homeworkStore.assignments.reduce((groups, item) => {
    const key = item.coachId
    if (!groups[key]) groups[key] = []
    groups[key].push(item)
    return groups
  }, {})
})

async function handleMarkComplete(assignmentId) {
  try {
    await homeworkStore.markComplete(assignmentId)
  } catch {
    $q.notify({ type: 'negative', message: t('common.errorGeneric') })
  }
}

watch(
  playerId,
  (val) => {
    if (val) homeworkStore.fetchDrills(val)
  },
  { immediate: true },
)

// skillars-deferred-99 AC14: first video load failure → silent one-shot refetch of this player's
// homework drills for a fresh signed URL (homeworkStore, keyed by playerId — not sessionStore).
// The DrillCard owns the toast + inline "unavailable" tile if the retry also fails.
function handleVideoError() {
  if (playerId.value) homeworkStore.fetchDrills(playerId.value)
}
</script>

<style lang="scss" scoped>
.locker-room__empty {
  min-height: 40vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.locker-room__drill-wrapper {
  border-radius: 12px;
  overflow: hidden;
}
</style>
