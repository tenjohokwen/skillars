<template>
  <q-card class="session-prep-card" style="min-width: 360px">
    <q-card-section>
      <div class="text-h6">{{ t('coach.sessionPrepTitle') }} — {{ playerName }}</div>
    </q-card-section>

    <q-card-section v-if="loading" class="flex flex-center q-py-lg">
      <q-spinner size="40px" />
    </q-card-section>

    <q-card-section v-else-if="error" class="text-negative">
      {{ t('coach.sessionPrepError') }}
    </q-card-section>

    <template v-else-if="prep">
      <q-card-section>
        <div class="text-caption" style="color: var(--text-secondary)">
          {{ lastSessionLabel }}
        </div>
        <template v-if="prep.lastSessionDate">
          <div v-if="hasRecap" class="q-mt-sm">
            <div class="text-caption text-weight-bold">
              {{ t('coach.sessionPrepWhatWeDidLabel') }}
            </div>
            <div class="text-body2">{{ prep.lastWhatWeDid }}</div>
            <div class="text-caption text-weight-bold q-mt-xs">
              {{ t('coach.sessionPrepWhatsNextLabel') }}
            </div>
            <div class="text-body2">{{ prep.lastWhatsNext }}</div>
          </div>
          <div v-else class="text-body2 q-mt-sm">{{ t('coach.sessionPrepNoRecapYet') }}</div>
        </template>
      </q-card-section>

      <q-card-section>
        <div class="text-caption text-weight-bold">
          {{ t('coach.sessionPrepFocusThreadLabel') }}
        </div>
        <div v-if="prep.focusThreadSkillCodes.length" class="row q-gutter-xs q-mt-xs">
          <q-badge v-for="code in prep.focusThreadSkillCodes" :key="code" color="primary">
            {{ skillDisplayName(code) }}
          </q-badge>
        </div>
        <div v-else class="text-body2 q-mt-xs">{{ t('coach.sessionPrepNoFocusThreadYet') }}</div>
      </q-card-section>

      <q-card-section>
        <q-badge v-if="prep.openNeglectedSkillCount > 0" color="negative">
          {{ t('coach.myPlayersNeglectedSkillCount', { count: prep.openNeglectedSkillCount }) }}
        </q-badge>
        <div v-else class="text-body2">{{ t('coach.sessionPrepNoNeglectedSkills') }}</div>
      </q-card-section>

      <q-card-actions align="right">
        <q-btn
          unelevated
          color="primary"
          :label="t('coach.sessionPrepPlanButton')"
          :to="{ name: 'coach-session-builder', params: { bookingId: props.bookingId } }"
        />
      </q-card-actions>
    </template>
  </q-card>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { getSessionPrep } from 'src/api/development.api'
import { useDevelopmentStore } from 'src/stores/development.store'

const props = defineProps({
  bookingId: { type: String, required: true },
  playerName: { type: String, required: true },
})

const { t, locale } = useI18n()
const developmentStore = useDevelopmentStore()

const loading = ref(true)
const error = ref(false)
const prep = ref(null)

const hasRecap = computed(() => !!(prep.value?.lastWhatWeDid || prep.value?.lastWhatsNext))

const lastSessionLabel = computed(() => {
  if (!prep.value?.lastSessionDate) return t('coach.myPlayersNeverTrainedTogether')
  return t('coach.myPlayersLastSessionLabel', {
    date: new Date(prep.value.lastSessionDate).toLocaleDateString(locale.value),
  })
})

// skillars-12-3 code review (2026-10-15) Patch, AC7: focus-thread badges were rendering raw
// internal skill codes (e.g. "PAC") untranslated in all three locales — resolve to the shared
// skill-definitions dictionary's displayName, matching ParentDevelopmentPortalPage.vue's own
// neglectedSkillNames precedent, falling back to the raw code only if the dictionary lookup misses.
function skillDisplayName(code) {
  return developmentStore.skillDefinitions.find((s) => s.code === code)?.displayName ?? code
}

onMounted(async () => {
  try {
    const [prepResult] = await Promise.all([
      getSessionPrep(props.bookingId),
      developmentStore.fetchSkillDefinitions(),
    ])
    prep.value = prepResult
  } catch {
    error.value = true
  } finally {
    loading.value = false
  }
})
</script>
