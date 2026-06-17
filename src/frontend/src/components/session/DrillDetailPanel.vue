<template>
  <!-- Mobile: bottom sheet — max-height applied here so Quasar's chrome is included in the 75vh cap -->
  <q-bottom-sheet v-if="isMobile" v-model="open" @hide="emit('close')" class="drill-detail-panel__sheet">
    <div class="drill-detail-panel__content">
      <template v-if="drill">
        <div class="drill-detail-panel__video-area q-mb-md">
          <video
            v-if="drill.videoUrl"
            controls
            :src="drill.videoUrl"
            class="drill-detail-panel__video"
          />
          <div v-else class="drill-detail-panel__no-video text-center q-pa-md">
            <q-icon name="videocam_off" size="48px" class="q-mb-sm" />
            <div class="text-caption text-secondary">Video preview available after upload</div>
          </div>
        </div>

        <q-list dense bordered separator class="q-mb-md rounded-borders">
          <q-item>
            <q-item-section>
              <q-item-label overline>{{ t('session.drillLibrary.detail.metadata') }}</q-item-label>
            </q-item-section>
          </q-item>
          <q-item>
            <q-item-section><q-item-label caption>Difficulty</q-item-label></q-item-section>
            <q-item-section side>{{ drill.metadata?.difficultyTier }}</q-item-section>
          </q-item>
          <q-item>
            <q-item-section><q-item-label caption>Group Size</q-item-label></q-item-section>
            <q-item-section side>{{ drill.metadata?.recommendedGroupSize }}</q-item-section>
          </q-item>
          <q-item>
            <q-item-section><q-item-label caption>Equipment</q-item-label></q-item-section>
            <q-item-section side>{{
              (drill.metadata?.equipmentRequired ?? []).join(', ')
            }}</q-item-section>
          </q-item>
        </q-list>

        <div class="q-mb-md">
          <div class="text-subtitle2 q-mb-sm">
            {{ t('session.drillLibrary.detail.sluBreakdown') }}
          </div>
          <q-list dense>
            <q-item v-for="item in sluBreakdown" :key="item.skill">
              <q-item-section>{{ item.skill }}</q-item-section>
              <q-item-section side>{{ item.slu }} SLU</q-item-section>
            </q-item>
          </q-list>
        </div>

        <div v-if="drill.metadata?.setupDiagram" class="q-mb-md">
          <img
            :src="drill.metadata.setupDiagram"
            alt="Setup diagram"
            style="width: 100%; border-radius: 8px"
          />
        </div>

        <div class="q-mb-md">
          <div class="text-subtitle2 q-mb-sm">
            {{ t('session.drillLibrary.detail.coachingPoints') }}
          </div>
          <ul>
            <li
              v-for="(point, idx) in drill.metadata?.coachingPoints"
              :key="idx"
              class="text-body2"
            >
              {{ point }}
            </li>
          </ul>
        </div>

        <div v-if="drill.tags?.length" class="q-mb-md">
          <div class="text-subtitle2 q-mb-sm">Tags</div>
          <div class="row q-gutter-xs">
            <q-chip v-for="tag in drill.tags" :key="tag" size="sm">{{ tag }}</q-chip>
          </div>
        </div>
      </template>
    </div>
  </q-bottom-sheet>

  <!-- Desktop: right-side dialog -->
  <q-dialog v-else v-model="open" position="right" full-height @hide="emit('close')">
    <q-card class="drill-detail-panel__desktop-card glass-card">
      <q-card-section class="row items-center q-pb-none">
        <div class="text-h6">{{ drill?.name }}</div>
        <q-space />
        <q-btn icon="close" flat round dense v-close-popup />
      </q-card-section>
      <q-separator />
      <q-scroll-area style="height: calc(100% - 60px)">
        <q-card-section>
          <template v-if="drill">
            <div class="drill-detail-panel__video-area q-mb-md">
              <video
                v-if="drill.videoUrl"
                controls
                :src="drill.videoUrl"
                class="drill-detail-panel__video"
              />
              <div v-else class="drill-detail-panel__no-video text-center q-pa-md">
                <q-icon name="videocam_off" size="48px" class="q-mb-sm" />
                <div class="text-caption text-secondary">Video preview available after upload</div>
              </div>
            </div>

            <q-list dense bordered separator class="q-mb-md rounded-borders">
              <q-item>
                <q-item-section>
                  <q-item-label overline>{{
                    t('session.drillLibrary.detail.metadata')
                  }}</q-item-label>
                </q-item-section>
              </q-item>
              <q-item>
                <q-item-section><q-item-label caption>Difficulty</q-item-label></q-item-section>
                <q-item-section side>{{ drill.metadata?.difficultyTier }}</q-item-section>
              </q-item>
              <q-item>
                <q-item-section><q-item-label caption>Group Size</q-item-label></q-item-section>
                <q-item-section side>{{ drill.metadata?.recommendedGroupSize }}</q-item-section>
              </q-item>
              <q-item>
                <q-item-section><q-item-label caption>Equipment</q-item-label></q-item-section>
                <q-item-section side>{{
                  (drill.metadata?.equipmentRequired ?? []).join(', ')
                }}</q-item-section>
              </q-item>
            </q-list>

            <div class="q-mb-md">
              <div class="text-subtitle2 q-mb-sm">
                {{ t('session.drillLibrary.detail.sluBreakdown') }}
              </div>
              <q-list dense>
                <q-item v-for="item in sluBreakdown" :key="item.skill">
                  <q-item-section>{{ item.skill }}</q-item-section>
                  <q-item-section side>{{ item.slu }} SLU</q-item-section>
                </q-item>
              </q-list>
            </div>

            <div v-if="drill.metadata?.setupDiagram" class="q-mb-md">
              <img
                :src="drill.metadata.setupDiagram"
                alt="Setup diagram"
                style="width: 100%; border-radius: 8px"
              />
            </div>

            <div class="q-mb-md">
              <div class="text-subtitle2 q-mb-sm">
                {{ t('session.drillLibrary.detail.coachingPoints') }}
              </div>
              <ul>
                <li
                  v-for="(point, idx) in drill.metadata?.coachingPoints"
                  :key="idx"
                  class="text-body2"
                >
                  {{ point }}
                </li>
              </ul>
            </div>

            <div v-if="drill.tags?.length" class="q-mb-md">
              <div class="text-subtitle2 q-mb-sm">Tags</div>
              <div class="row q-gutter-xs">
                <q-chip v-for="tag in drill.tags" :key="tag" size="sm">{{ tag }}</q-chip>
              </div>
            </div>
          </template>
        </q-card-section>
      </q-scroll-area>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useQuasar } from 'quasar'
import { useI18n } from 'vue-i18n'

defineOptions({ name: 'DrillDetailPanel' })

const props = defineProps({
  drill: { type: Object, default: null },
  isOpen: { type: Boolean, default: false },
})

const emit = defineEmits(['close'])

const $q = useQuasar()
const { t } = useI18n()

const isMobile = computed(() => $q.screen.lt.sm)

// Local ref avoids Quasar's sheet snapping back while the parent processes the close event
const open = ref(props.isOpen)
watch(() => props.isOpen, (val) => { open.value = val })

const sluBreakdown = computed(() => {
  if (!props.drill?.metadata) return []
  const { repDensity, skillWeighting } = props.drill.metadata
  return Object.entries(skillWeighting ?? {}).map(([skill, weight]) => ({
    skill,
    slu: Math.round((repDensity * weight) / 100),
  }))
})
</script>

<style scoped lang="scss">
.drill-detail-panel {
  &__sheet {
    max-height: 75vh;
    overflow-y: auto;
  }

  &__content {
    padding: 16px;
  }

  &__desktop-card {
    min-width: 420px;
    height: 100%;
  }

  &__video-area {
    background: var(--glass-surface, rgba(255, 255, 255, 0.05));
    border-radius: 8px;
    overflow: hidden;
    min-height: 160px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__video {
    width: 100%;
    max-height: 280px;
    object-fit: contain;
  }

  &__no-video {
    color: var(--color-text-secondary);
    width: 100%;
  }
}
</style>
