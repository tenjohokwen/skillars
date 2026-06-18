<template>
  <q-page class="q-pa-md">
    <div class="row items-center q-mb-lg">
      <div class="gradient-text text-h5 q-mr-sm">{{ t('player.lockerRoomTitle') }}</div>
    </div>

    <div v-if="homeworkStore.loading" class="flex flex-center q-pa-xl">
      <q-spinner-dots size="36px" color="primary" />
    </div>

    <div v-else-if="!homeworkStore.assignments.length" class="locker-room__empty text-center q-pa-xl">
      <q-icon name="sports_soccer" size="64px" color="grey-5" />
      <div class="text-h6 q-mt-md">{{ t('player.homeworkEmptyTitle') }}</div>
      <div class="text-body2 text-secondary q-mt-sm">{{ t('player.homeworkEmptySubtitle') }}</div>
    </div>

    <template v-else>
      <div v-for="(group, coachId) in groupedByCoach" :key="coachId" class="q-mb-xl">
        <div v-if="Object.keys(groupedByCoach).length > 1" class="text-subtitle2 q-mb-sm text-secondary">
          {{ t('player.assignedBy', { coach: group[0].coachDisplayName }) }}
        </div>
        <div class="locker-room__drills">
          <div
            v-for="item in group"
            :key="item.assignmentId"
            class="locker-room__drill-wrapper q-mb-md"
          >
            <DrillCard
              :drill="item.drill"
              context="locker-room"
              class="full-width"
            />
            <div class="locker-room__completion-row row items-center q-mt-xs q-px-sm">
              <q-checkbox
                :model-value="item.completed"
                :label="item.completed ? t('player.homeworkCompleted') : t('player.homeworkMarkDone')"
                :color="item.completed ? 'positive' : 'primary'"
                :disable="item.completed"
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

watch(playerId, val => { if (val) homeworkStore.fetchDrills(val) }, { immediate: true })
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
