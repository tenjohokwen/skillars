<template>
  <div>
    <div class="row items-center q-mb-md">
      <div class="text-subtitle1 text-weight-medium">{{ $t('development.report.panelTitle') }}</div>
      <q-space />
      <q-btn
        v-if="isCoach && canGenerate"
        color="primary"
        icon="picture_as_pdf"
        :label="$t('development.report.generate')"
        size="sm"
        @click="showDialog = true"
      />
    </div>

    <q-inner-loading :showing="store.reportsLoading" />

    <q-banner v-if="store.reportsError && !store.reportsLoading" class="bg-negative text-white q-mb-sm" rounded>
      <template #avatar><q-icon name="error" /></template>
      {{ store.reportsError }}
    </q-banner>

    <q-list v-else-if="store.reports.length" separator>
      <q-item v-for="report in store.reports" :key="report.id">
        <q-item-section avatar>
          <q-icon name="description" color="primary" />
        </q-item-section>
        <q-item-section>
          <q-item-label>{{ $t('development.report.byCoach', { coach: report.coachName }) }}</q-item-label>
          <q-item-label caption>{{ formatDate(report.generatedAt) }}</q-item-label>
        </q-item-section>
        <q-item-section side>
          <q-btn
            flat
            icon="download"
            :label="$t('development.report.download')"
            size="sm"
            :href="report.signedDownloadUrl || undefined"
            :disable="!report.signedDownloadUrl"
            target="_blank"
            type="a"
          />
        </q-item-section>
      </q-item>
    </q-list>

    <div v-else-if="!store.reportsLoading && !store.error" class="text-body2 text-grey-6 q-pa-sm">
      {{ $t('development.report.noReports') }}
    </div>

    <GenerateReportDialog
      v-model="showDialog"
      :player-id="playerId"
      :player-name="playerName"
      @generated="store.fetchReports(playerId)"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { date } from 'quasar'
import { useDevelopmentStore } from 'src/stores/development.store'
import { useAuthStore } from 'src/stores/auth.store'
import GenerateReportDialog from './GenerateReportDialog.vue'

const props = defineProps({
  playerId: { type: [Number, String], required: true },
  playerName: { type: String, default: '' },
  isCoach: { type: Boolean, default: false },
})

const store = useDevelopmentStore()
const authStore = useAuthStore()
const showDialog = ref(false)

const canGenerate = computed(() => authStore.coachTier != null && authStore.coachTier !== 'SCOUT')

function formatDate(isoString) {
  return date.formatDate(isoString, 'DD MMM YYYY HH:mm')
}

onMounted(() => {
  if (!props.playerId) return
  store.fetchReports(props.playerId)
})
</script>
