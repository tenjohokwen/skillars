<template>
  <q-page class="marketplace-page">
    <!-- City search — primary entry point (AC 3) -->
    <div class="marketplace-page__search-bar">
      <q-input
        v-model="filters.city"
        outlined
        :label="t('marketplace.searchByCity')"
        :placeholder="t('marketplace.searchByCityPlaceholder')"
        class="city-input"
        clearable
        @keyup.enter="onSearch"
        @clear="onCityCleared"
      >
        <template #append>
          <q-btn
            unelevated
            color="primary"
            icon="search"
            :label="t('marketplace.search')"
            :disable="!cityEntered"
            @click="onSearch"
          />
        </template>
      </q-input>
    </div>

    <!-- Secondary filters — visible only after a city is entered -->
    <div v-if="cityEntered" class="marketplace-page__filters">
      <q-input v-model="filters.district" dense outlined :label="t('marketplace.filterDistrict')"
               clearable @update:model-value="onFilterChange" />
      <q-select v-model="filters.ageGroup" dense outlined :label="t('marketplace.filterAgeGroup')"
                :options="ageGroupOptions" emit-value map-options clearable
                @update:model-value="onFilterChange" />
      <q-select v-model="filters.language" dense outlined :label="t('marketplace.filterLanguage')"
                :options="languageOptions" emit-value map-options clearable
                @update:model-value="onFilterChange" />
      <q-select v-model="filters.sortBy" dense outlined :label="t('marketplace.sortBy')"
                :options="sortOptions" emit-value map-options
                @update:model-value="onFilterChange" />
      <q-btn v-if="hasActiveFilters" flat dense :label="t('marketplace.clearFilters')"
             icon="close" @click="onClearFilters" />
    </div>

    <!-- Prompt state — no city entered yet (NOT the UX-DR25 empty state) -->
    <div v-if="!cityEntered" class="marketplace-page__prompt">
      <q-icon name="search" size="64px" color="grey-4" />
      <div class="text-h6 q-mt-md">{{ t('marketplace.enterCityPrompt') }}</div>
      <div class="text-body2 text-secondary q-mt-xs">{{ t('marketplace.enterCitySubtitle') }}</div>
    </div>

    <!-- Loading skeleton (UX-DR26) -->
    <div v-else-if="loading" class="marketplace-page__grid">
      <div v-for="n in 6" :key="n" class="glass-card coach-card-skeleton">
        <q-skeleton height="180px" square />
        <div class="q-pa-md">
          <q-skeleton type="text" width="60%" />
          <q-skeleton type="text" width="40%" class="q-mt-sm" />
          <q-skeleton type="text" width="30%" class="q-mt-sm" />
        </div>
      </div>
    </div>

    <!-- Empty state (UX-DR25) — city entered, search done, no results -->
    <div v-else-if="cityEntered && !loading && coaches.length === 0" class="marketplace-page__empty">
      <q-icon name="search_off" size="64px" color="grey-5" />
      <div class="text-h6 q-mt-md">{{ t('marketplace.noCoachesFound') }}</div>
      <div class="text-body2 text-secondary q-mt-xs">
        {{ t('marketplace.noCoachesFoundInCity', { city: filters.city }) }}
      </div>
      <div class="q-mt-md row q-gutter-sm justify-center">
        <q-btn v-if="hasActiveFilters" unelevated color="primary"
               :label="t('marketplace.clearFilters')" @click="onClearFilters" />
        <q-btn outline color="primary"
               :label="t('marketplace.tryAnotherCity')" @click="onCityCleared" />
      </div>
    </div>

    <!-- Coach grid -->
    <template v-else>
      <div class="marketplace-page__results-header">
        <span class="text-caption text-secondary">
          {{ t('marketplace.resultsCount', { count: totalElements }) }}
        </span>
      </div>

      <div class="marketplace-page__grid">
        <CoachCard
          v-for="coach in coaches"
          :key="coach.id"
          :coach="coach"
          @click="goToProfile"
        />
      </div>

      <!-- Load-more -->
      <div v-if="hasNext" class="marketplace-page__load-more">
        <q-btn
          outline
          color="primary"
          :label="t('marketplace.loadMore')"
          :loading="loadingMore"
          @click="store.fetchNextPage()"
        />
      </div>
    </template>
  </q-page>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useI18n } from 'vue-i18n'
import { useMarketplaceStore } from 'src/stores/marketplace.store'
import CoachCard from 'src/components/marketplace/CoachCard.vue'

const { t } = useI18n()
const router = useRouter()
const route  = useRoute()
const store  = useMarketplaceStore()
const { coaches, loading, loadingMore, filters, hasActiveFilters,
        cityEntered, hasNext, totalElements } = storeToRefs(store)

const ageGroupOptions = [
  { label: t('marketplace.ageGroupU10'),   value: 'U10' },
  { label: t('marketplace.ageGroup1012'),  value: 'AGE_10_12' },
  { label: t('marketplace.ageGroup1317'),  value: 'AGE_13_17' },
  { label: t('marketplace.ageGroupAdult'), value: 'ADULT' },
]

const languageOptions = ['German', 'English', 'Turkish', 'Arabic'].map(l => ({ label: l, value: l }))

const sortOptions = [
  { label: t('marketplace.sortName'),      value: 'displayName' },
  { label: t('marketplace.sortPrice'),     value: 'price' },
  { label: t('marketplace.sortRatingStub'), value: 'rating', disable: true },
]

onMounted(() => {
  // Restore filters from URL (AC 3 — back-button preserves state)
  store.syncFiltersFromRoute(route.query)
  // If city was in URL, fire search immediately (user came back from a profile page)
  if (store.cityEntered) store.fetchCoaches()
})

function onSearch() {
  if (!store.cityEntered) return
  router.replace({ query: store.buildRouteQuery() })
  store.fetchCoaches()
}

function onFilterChange() {
  router.replace({ query: store.buildRouteQuery() })
  store.fetchCoaches()
}

function onClearFilters() {
  store.clearFilters()
  router.replace({ query: store.buildRouteQuery() }) // city stays in URL
}

function onCityCleared() {
  store.resetSearch()
  router.replace({ query: {} })
}

function goToProfile(coachId) {
  router.push({ path: `/coaches/${coachId}`, query: { returnUrl: route.fullPath } })
}
</script>

<style lang="scss" scoped>
.marketplace-page {
  max-width: 1400px;
  margin: 0 auto;
  padding: 24px;

  &__search-bar {
    margin-bottom: 20px;
    .city-input { max-width: 640px; }
  }

  &__filters {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
    margin-bottom: 24px;
  }

  &__results-header {
    margin-bottom: 16px;
  }

  &__grid {
    display: grid;
    gap: 20px;
    grid-template-columns: repeat(3, 1fr);          // ≥1200px
    @media (max-width: 1199px) { grid-template-columns: repeat(2, 1fr); }  // 768–1199px
    @media (max-width: 767px)  { grid-template-columns: 1fr; }             // mobile
  }

  &__prompt, &__empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 80px 24px;
    text-align: center;
    color: var(--text-secondary);
  }

  &__load-more {
    display: flex;
    justify-content: center;
    padding: 32px 0;
  }
}

.coach-card-skeleton {
  border-radius: 28px;
  overflow: hidden;
}
</style>
