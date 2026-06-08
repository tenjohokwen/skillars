<template>
  <q-page padding>
    <div class="text-h5 q-mb-md">System Health</div>

    <q-inner-loading :showing="isLoading" />

    <div v-if="!isLoading && health">
      <div class="q-mb-md">
        <q-chip
          :color="health.status === 'UP' ? 'positive' : 'negative'"
          text-color="white"
          icon="circle"
        >
          {{ health.status }}
        </q-chip>
      </div>

      <q-banner v-if="!health.components" class="q-mb-md" color="warning" rounded>
        Admin access required to view health details.
      </q-banner>

      <div v-if="health.components">
        <q-card
          v-for="(component, name) in health.components"
          :key="name"
          class="q-mb-md"
        >
          <q-card-section>
            <div class="row items-center q-mb-sm">
              <div class="text-subtitle1 q-mr-sm">{{ name }}</div>
              <q-badge
                :color="component.status === 'UP' ? 'positive' : 'negative'"
                :label="component.status"
              />
            </div>
            <div
              v-for="(val, key) in component.details ?? {}"
              :key="key"
              class="text-caption q-mt-xs"
            >
              <span class="text-weight-medium">{{ key }}:</span> {{ val }}
            </div>
          </q-card-section>
        </q-card>
      </div>
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
    // Spring returns HTTP 503 when overall status is DOWN, but the body still contains
    // the full health JSON — extract it so the dashboard can display the DOWN state.
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
