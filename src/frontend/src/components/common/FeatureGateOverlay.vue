<script setup>
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({
  requiredTier: {
    type: String,
    required: true,
  },
  featureLabel: {
    type: String,
    required: true,
  },
})

const emit = defineEmits(['upgrade-clicked'])
</script>

<template>
  <div class="feature-gate-wrapper">
    <div class="gate-preview-blur" aria-hidden="true">
      <slot />
    </div>
    <div class="gate-overlay glass-card">
      <p class="gate-tier-label text-caption q-mb-xs">
        {{ t('featureGate.requiredTier', { tier: requiredTier }) }}
      </p>
      <p class="gate-feature-label text-subtitle2 q-mb-md">{{ featureLabel }}</p>
      <p class="gate-description text-body2 q-mb-lg">
        {{ t('featureGate.description', { tier: requiredTier }) }}
      </p>
      <q-btn
        color="primary"
        :label="t('featureGate.upgradeCTA')"
        unelevated
        @click="emit('upgrade-clicked')"
      />
    </div>
  </div>
</template>

<style lang="scss" scoped>
.feature-gate-wrapper {
  position: relative;
}
.gate-preview-blur {
  filter: blur(6px);
  pointer-events: none;
  user-select: none;
}
.gate-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 24px;
}
</style>
