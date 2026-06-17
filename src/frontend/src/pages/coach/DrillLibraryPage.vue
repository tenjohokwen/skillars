<template>
  <q-page class="drill-library-page q-pa-md">
    <h1 class="text-h5 q-mb-md">{{ t('session.drillLibrary.title') }}</h1>

    <!-- Tab bar -->
    <q-tabs v-model="selectedLibrary" dense class="q-mb-md" @update:model-value="onTabChange">
      <q-tab name="PLATFORM" :label="t('session.drillLibrary.platformTab')" />
      <q-tab name="PRIVATE" :label="t('session.drillLibrary.myLibraryTab')" />
    </q-tabs>

    <!-- Search + Filter bar -->
    <div class="row q-gutter-sm q-mb-md items-center">
      <q-input
        v-model="localSearchQuery"
        :placeholder="t('session.drillLibrary.searchPlaceholder')"
        dense
        outlined
        clearable
        class="col"
        @update:model-value="onSearchInput"
      >
        <template #prepend>
          <q-icon name="search" />
        </template>
      </q-input>

      <q-btn
        outline
        :label="t('session.drillLibrary.filterButton')"
        icon="filter_list"
        @click="showFilters = true"
      >
        <q-badge v-if="activeFilterCount > 0" color="primary" floating>
          {{ activeFilterCount }}
        </q-badge>
      </q-btn>
    </div>

    <!-- Loading state -->
    <div v-if="sessionStore.loading" class="flex flex-center q-pa-xl">
      <q-spinner-dots size="48px" color="primary" />
    </div>

    <!-- Drill grid -->
    <template v-else>
      <!-- Search/filter empty state -->
      <div
        v-if="sessionStore.drills.length === 0 && (localSearchQuery || activeFilterCount > 0)"
        class="flex flex-center column q-pa-xl text-center"
      >
        <q-icon name="search_off" size="64px" color="grey" class="q-mb-md" />
        <div class="text-h6 q-mb-sm">{{ t('session.drillLibrary.noResults') }}</div>
        <div class="text-caption text-grey q-mb-md">
          {{ t('session.drillLibrary.noResultsHint') }}
        </div>
        <div class="row q-gutter-sm">
          <q-btn
            v-if="localSearchQuery"
            outline
            :label="t('session.drillLibrary.clearSearch')"
            @click="clearSearch"
          />
          <q-btn
            v-if="activeFilterCount > 0"
            outline
            :label="t('session.drillLibrary.clearFilters')"
            @click="clearFilters"
          />
        </div>
      </div>

      <!-- Genuinely empty library state -->
      <div
        v-else-if="sessionStore.drills.length === 0 && !localSearchQuery && activeFilterCount === 0"
        class="flex flex-center column q-pa-xl text-center"
      >
        <q-icon name="sports_soccer" size="64px" color="grey" class="q-mb-md" />
        <div class="text-h6 q-mb-md">{{ t('session.drillLibrary.emptyLibrary') }}</div>
        <q-btn
          v-if="selectedLibrary === 'PRIVATE'"
          color="primary"
          :label="t('session.drillLibrary.clonePlatformDrill')"
          @click="selectedLibrary = 'PLATFORM'"
        />
      </div>

      <!-- Drill cards grid -->
      <div v-else class="drill-library-page__grid">
        <DrillCard
          v-for="drill in sessionStore.drills"
          :key="drill.id"
          :drill="drill"
          context="library"
          @open-detail="openDetail(drill)"
          @clone="handleClone"
          @edit-clone="handleEditClone"
        />
      </div>
    </template>

    <!-- Filter sheet -->
    <q-dialog v-model="showFilters" position="bottom">
      <q-card style="width: 100%; max-width: 600px">
        <q-card-section>
          <div class="text-h6">{{ t('session.drillLibrary.filterButton') }}</div>
        </q-card-section>
        <q-card-section class="q-pt-none">
          <q-select
            v-model="localFilters.skill"
            label="Skill"
            :options="skillOptions"
            clearable
            outlined
            dense
            class="q-mb-sm"
          />
          <q-select
            v-model="localFilters.difficultyTier"
            label="Difficulty Tier"
            :options="['U8', 'U10', 'U12', 'U14', 'U16', 'U18', 'Adult']"
            clearable
            outlined
            dense
            class="q-mb-sm"
          />
          <q-select
            v-model="localFilters.equipment"
            label="Equipment"
            :options="equipmentOptions"
            clearable
            outlined
            dense
            multiple
            class="q-mb-sm"
          />
          <q-toggle v-model="localFilters.weakFootBias" label="Weak Foot Bias only" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('session.drillLibrary.clearFilters')" @click="clearFilters" />
          <q-btn color="primary" label="Apply" @click="applyFilters" />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- Detail panel -->
    <DrillDetailPanel
      :drill="sessionStore.selectedDrill"
      :is-open="!!sessionStore.selectedDrill"
      @close="sessionStore.selectedDrill = null"
    />
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useSessionStore } from 'src/stores/session.store'
import DrillCard from 'src/components/session/DrillCard.vue'
import DrillDetailPanel from 'src/components/session/DrillDetailPanel.vue'

function useDebounce(fn, delay) {
  let timer
  return (...args) => {
    clearTimeout(timer)
    timer = setTimeout(() => fn(...args), delay)
  }
}

const { t } = useI18n()
const $q = useQuasar()
const sessionStore = useSessionStore()

const selectedLibrary = ref('PLATFORM')
const localSearchQuery = ref('')
const showFilters = ref(false)

const localFilters = ref({
  skill: null,
  difficultyTier: null,
  equipment: null,
  weakFootBias: null,
})

const skillOptions = [
  'ball_mastery',
  'dribbling',
  'passing',
  'shooting',
  'finishing',
  'defending',
  'heading',
  'positioning',
  'coordination',
]

const equipmentOptions = ['ball', 'cones', 'bibs', 'goals', 'poles']

const activeFilterCount = computed(
  () =>
    Object.values(sessionStore.activeFilters).filter(
      (v) => v !== null && v !== undefined && v !== '',
    ).length,
)

const debouncedSearch = useDebounce(() => {
  sessionStore.searchQuery = localSearchQuery.value
  sessionStore.searchDrills(selectedLibrary.value)
}, 300)

function onSearchInput() {
  debouncedSearch()
}

function onTabChange(library) {
  localSearchQuery.value = ''
  sessionStore.searchQuery = ''
  sessionStore.activeFilters = {
    skill: null,
    difficultyTier: null,
    equipment: null,
    weakFootBias: null,
  }
  localFilters.value = { skill: null, difficultyTier: null, equipment: null, weakFootBias: null }
  sessionStore.fetchDrills(library)
}

function clearSearch() {
  localSearchQuery.value = ''
  sessionStore.searchQuery = ''
  sessionStore.searchDrills(selectedLibrary.value)
}

function clearFilters() {
  localFilters.value = { skill: null, difficultyTier: null, equipment: null, weakFootBias: null }
  sessionStore.activeFilters = {
    skill: null,
    difficultyTier: null,
    equipment: null,
    weakFootBias: null,
  }
  showFilters.value = false
  sessionStore.searchDrills(selectedLibrary.value)
}

function applyFilters() {
  sessionStore.activeFilters = { ...localFilters.value }
  showFilters.value = false
  sessionStore.searchDrills(selectedLibrary.value)
}

function openDetail(drill) {
  sessionStore.selectedDrill = drill
}

async function handleClone(drillId) {
  try {
    await sessionStore.cloneDrill(drillId)
    const clonedDrill = sessionStore.drills.find((d) => d.id === drillId)
    $q.notify({
      message: t('session.drillLibrary.addedToLibrary'),
      color: 'positive',
      actions: [
        {
          label: t('session.drillLibrary.viewInLibrary'),
          color: 'white',
          handler: () => handleEditClone(clonedDrill?.cloneId),
        },
      ],
    })
  } catch {
    $q.notify({ message: 'Failed to clone drill', color: 'negative' })
  }
}

async function handleEditClone(cloneId) {
  if (!cloneId) return
  selectedLibrary.value = 'PRIVATE'
  localSearchQuery.value = ''
  sessionStore.searchQuery = ''
  sessionStore.activeFilters = { skill: null, difficultyTier: null, equipment: null, weakFootBias: null }
  localFilters.value = { skill: null, difficultyTier: null, equipment: null, weakFootBias: null }
  await sessionStore.fetchDrills('PRIVATE')
  const clone = sessionStore.drills.find((d) => d.id === cloneId)
  if (clone) sessionStore.selectedDrill = clone
}

onMounted(() => {
  sessionStore.fetchDrills('PLATFORM')
})
</script>

<style scoped lang="scss">
.drill-library-page {
  &__grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
    gap: 16px;

    @media (max-width: 375px) {
      grid-template-columns: 1fr;
    }
  }
}
</style>
