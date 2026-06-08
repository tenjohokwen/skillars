<template>
  <q-page>
    <div class="app-page fade-in">

      <div class="page-header q-mb-xl">
        <div class="text-page-title">System Health</div>
        <div class="text-meta">Real-time infrastructure status</div>
      </div>

      <div v-if="isLoading" class="flex flex-center q-py-xl">
        <q-spinner-dots size="40px" style="color: var(--accent-primary)" />
      </div>

      <template v-else-if="health">
        <!-- Overall status pill -->
        <div class="q-mb-xl">
          <span
            class="status-pill"
            :class="health.status === 'UP' ? 'status-pill--up' : 'status-pill--down'"
          >
            <q-icon :name="health.status === 'UP' ? 'check_circle' : 'cancel'" size="16px" />
            {{ health.status }}
          </span>
        </div>

        <q-banner v-if="!health.components" class="health-banner q-mb-lg" rounded>
          <template #avatar>
            <q-icon name="lock" style="color: var(--accent-warning)" />
          </template>
          Admin access required to view health details.
        </q-banner>

        <div v-if="health.components" class="components-grid">
          <div
            v-for="(component, name) in health.components"
            :key="name"
            class="glass-card component-card"
          >
            <div class="component-header">
              <div class="text-card-title">{{ name }}</div>
              <span
                class="status-pill status-pill--sm"
                :class="component.status === 'UP' ? 'status-pill--up' : 'status-pill--down'"
              >
                {{ component.status }}
              </span>
            </div>
            <div v-if="component.details" class="component-details q-mt-md">
              <div
                v-for="(val, key) in component.details"
                :key="key"
                class="detail-row"
              >
                <span class="text-label">{{ key }}</span>
                <span class="text-meta">{{ val }}</span>
              </div>
            </div>
          </div>
        </div>
      </template>

    </div>
  </q-page>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useQuasar } from 'quasar'
import { adminApi } from 'src/api/admin.api'

const $q = useQuasar()
const health = ref(null)
const isLoading = ref(false)

onMounted(async () => {
  isLoading.value = true
  try {
    health.value = await adminApi.getHealth()
  } catch (error) {
    if (error?.response?.data?.status) {
      health.value = error.response.data
    } else {
      $q.notify({ type: 'negative', message: 'Failed to load health status' })
    }
  } finally {
    isLoading.value = false
  }
})
</script>

<style lang="scss" scoped>
.page-header {
  border-bottom: 1px solid var(--border-soft);
  padding-bottom: 24px;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 16px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.5px;

  &--up {
    background: rgba(0, 255, 180, 0.12);
    color: var(--accent-primary);
  }
  &--down {
    background: rgba(255, 95, 122, 0.12);
    color: var(--accent-danger);
  }
  &--sm {
    font-size: 11px;
    padding: 4px 12px;
  }
}

.health-banner {
  background: rgba(255, 184, 77, 0.10) !important;
  color: var(--accent-warning) !important;
  border: 1px solid rgba(255, 184, 77, 0.20) !important;
}

.components-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 20px;
}

.component-card {
  padding: 24px;
}

.component-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.component-details {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
  border-bottom: 1px solid var(--border-soft);

  &:last-child {
    border-bottom: none;
  }
}
</style>
