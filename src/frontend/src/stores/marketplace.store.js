import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { searchCoaches } from 'src/api/marketplace.api'

// Note: Do NOT import useRouter/useRoute here — URL sync is handled in MarketplacePage.vue
export const useMarketplaceStore = defineStore('marketplace', () => {
  const coaches     = ref([])
  const loading     = ref(false)
  const loadingMore = ref(false)
  const error       = ref(null)

  // Pagination state
  const currentPage   = ref(0)
  const totalPages    = ref(0)
  const totalElements = ref(0)
  const hasNext       = ref(false)

  const filters = ref({
    city:     '',   // primary — search does not fire without a city value
    district: '',
    language: '',
    minPrice: null,
    maxPrice: null,
    ageGroup: '',
    skill:    '',
    sortBy:   'displayName',
  })

  // hasActiveFilters excludes city (city is not an optional "filter", it's the search entry)
  const hasActiveFilters = computed(() =>
    ['district', 'language', 'minPrice', 'maxPrice', 'ageGroup', 'skill'].some(
      k => filters.value[k] !== '' && filters.value[k] !== null
    )
  )

  const cityEntered = computed(() => filters.value.city.trim().length > 0)

  function syncFiltersFromRoute(query) {
    const parseNumber = (v) => { const n = Number(v); return v && !isNaN(n) ? n : null }
    filters.value = {
      city:     query.city     || '',
      district: query.district || '',
      language: query.language || '',
      minPrice: parseNumber(query.minPrice),
      maxPrice: parseNumber(query.maxPrice),
      ageGroup: query.ageGroup || '',
      skill:    query.skill    || '',
      sortBy:   query.sortBy   || 'displayName',
    }
  }

  function buildRouteQuery() {
    const q = {}
    Object.entries(filters.value).forEach(([k, v]) => {
      if (v !== '' && v !== null && !(k === 'sortBy' && v === 'displayName')) q[k] = v
    })
    return q
  }

  async function fetchCoaches() {
    if (!cityEntered.value) return  // guard: no search without city
    loading.value = true
    error.value = null
    currentPage.value = 0
    coaches.value = []
    hasNext.value = false      // reset so stale "Load More" never shows during loading
    totalElements.value = 0
    try {
      const params = buildApiParams(0)
      const res = await searchCoaches(params)
      applyPage(res.data)
    } catch (e) {
      error.value = e
    } finally {
      loading.value = false
    }
  }

  async function fetchNextPage() {
    if (!hasNext.value || loadingMore.value) return
    loadingMore.value = true
    try {
      const params = buildApiParams(currentPage.value + 1)
      const res = await searchCoaches(params)
      coaches.value = [...coaches.value, ...res.data.coaches]  // append for infinite-scroll UX
      currentPage.value   = res.data.page
      totalPages.value    = res.data.totalPages
      totalElements.value = res.data.totalElements
      hasNext.value       = res.data.hasNext
    } catch (e) {
      error.value = e
    } finally {
      loadingMore.value = false
    }
  }

  function buildApiParams(page) {
    const p = { page, size: 20 }
    Object.entries(filters.value).forEach(([k, v]) => {
      if (v !== '' && v !== null && !(k === 'sortBy' && v === 'displayName')) p[k] = v
    })
    return p
  }

  function applyPage(data) {
    coaches.value       = data.coaches
    currentPage.value   = data.page
    totalPages.value    = data.totalPages
    totalElements.value = data.totalElements
    hasNext.value       = data.hasNext
  }

  function clearFilters() {
    // Clears secondary filters only — city is preserved (it is the search entry point)
    filters.value = {
      ...filters.value,
      district: '', language: '', minPrice: null,
      maxPrice: null, ageGroup: '', skill: '', sortBy: 'displayName',
    }
    fetchCoaches()
  }

  function resetSearch() {
    coaches.value = []
    filters.value = {
      city: '', district: '', language: '', minPrice: null,
      maxPrice: null, ageGroup: '', skill: '', sortBy: 'displayName',
    }
    currentPage.value = 0
    totalPages.value = 0
    hasNext.value = false
  }

  return {
    coaches, loading, loadingMore, error,
    currentPage, totalPages, totalElements, hasNext,
    filters, hasActiveFilters, cityEntered,
    syncFiltersFromRoute, buildRouteQuery,
    fetchCoaches, fetchNextPage, clearFilters, resetSearch,
  }
})
