<template>
  <div v-if="hasContent" class="session-pack-tracker">
    <div class="tracker-title text-caption text-weight-medium q-mb-sm">
      {{ t('marketplace.sessionPacksOffered') }}
    </div>

    <div v-if="perSessionPrice != null" class="pack-row">
      <span class="pack-label">{{ t('marketplace.perSessionFrom', { price: formatPrice(perSessionPrice) }) }}</span>
    </div>

    <div v-for="pack in sessionPacks" :key="pack.sessionCount" class="pack-row">
      <span class="pack-label">{{ packLabel(pack) }}</span>
      <span class="pack-price">{{ formatPrice(pack.totalPrice) }}</span>
      <span v-if="perSessionPrice != null && pack.sessionCount > 1 && savings(pack)" class="pack-saving text-caption">
        {{ savings(pack) }}
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  sessionPacks: { type: Array, default: () => [] },
  perSessionPrice: { type: Number, default: null },
  currency: { type: String, default: 'EUR' },
})

const { t } = useI18n()

const hasContent = computed(
  () => props.sessionPacks.length > 0 || props.perSessionPrice != null,
)

function formatPrice(value) {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency: props.currency }).format(Number(value))
}

function packLabel(pack) {
  return pack.label || t('marketplace.sessionsFallbackLabel', { count: pack.sessionCount })
}

function savings(pack) {
  const full = props.perSessionPrice * pack.sessionCount
  const saved = full - pack.totalPrice
  if (saved <= 0) return ''
  return t('marketplace.savingsLabel', { amount: formatPrice(saved) })
}
</script>

<style lang="scss" scoped>
.session-pack-tracker {
  padding: 12px;
  border-radius: 8px;
  background: var(--surface-raised);
  border: 1px solid var(--border-subtle);
}

.pack-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
  color: var(--text-primary);
}

.pack-price {
  margin-left: auto;
  font-weight: 600;
}

.pack-saving {
  color: var(--accent-success);
}
</style>
