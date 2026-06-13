<template>
  <q-card class="glass-card coach-card" @click="$emit('click', coach.id)">
    <!-- Photo / Avatar -->
    <div class="coach-card__photo-wrap">
      <q-img
        v-if="coach.photoUrl"
        :src="coach.photoUrl"
        class="coach-card__photo"
        fit="cover"
        :ratio="1"
      />
      <div v-else class="coach-card__avatar-placeholder">
        <q-icon name="person" size="48px" color="white" />
      </div>
    </div>

    <q-card-section class="coach-card__body">
      <!-- Trust signals ABOVE price (UX-DR7) -->
      <div class="coach-card__trust-row">
        <VerificationBadge :tier="coach.verificationTier" />
        <div v-if="coach.capabilityBadges?.length" class="coach-card__capability-badges">
          <q-badge
            v-for="badge in coach.capabilityBadges"
            :key="badge"
            color="primary"
            class="q-mr-xs"
          >{{ badge }}</q-badge>
        </div>
      </div>

      <ReliabilityIndicator :strike-count="coach.reliabilityStrikeCount" class="q-mt-xs" />

      <!-- Name + location -->
      <div class="coach-card__name q-mt-sm">{{ coach.displayName }}</div>
      <div class="coach-card__location text-caption">
        <q-icon name="location_on" size="12px" />
        {{ [coach.city, coach.district].filter(Boolean).join(', ') }}
      </div>

      <!-- Specialties -->
      <div v-if="coach.topSpecialties.length" class="coach-card__specialties q-mt-xs">
        <q-chip
          v-for="s in coach.topSpecialties"
          :key="s"
          dense
          outline
          size="sm"
        >{{ s }}</q-chip>
      </div>

      <!-- Star rating -->
      <div class="coach-card__rating q-mt-xs">
        <q-rating
          :model-value="coach.aggregateRating"
          readonly
          size="14px"
          color="amber"
          max="5"
        />
        <span class="text-caption q-ml-xs">
          {{ coach.aggregateRating.toFixed(1) }}
          ({{ t('marketplace.reviewCount', { count: coach.reviewCount }) }})
        </span>
      </div>

      <!-- Price — BELOW trust signals (UX-DR7) -->
      <div class="coach-card__price q-mt-sm">
        <span class="text-h6">€{{ Number(coach.perSessionPrice).toFixed(2) }}</span>
        <span class="text-caption q-ml-xs text-secondary">/ {{ t('marketplace.perSession') }}</span>
      </div>
    </q-card-section>
  </q-card>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
import VerificationBadge from './VerificationBadge.vue'
import ReliabilityIndicator from './ReliabilityIndicator.vue'

defineProps({
  coach: { type: Object, required: true },
})

defineEmits(['click'])

const { t } = useI18n()
</script>

<style lang="scss" scoped>
.coach-card {
  cursor: pointer;
  transition: transform 0.15s ease;

  &:hover { transform: translateY(-2px); }

  &__photo-wrap {
    height: 180px;
    overflow: hidden;
    border-radius: 28px 28px 0 0;
    background: linear-gradient(135deg, var(--accent-primary), var(--accent-secondary));
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__photo {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &__body { padding: 12px 16px 16px; }

  &__trust-row {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
  }

  &__name {
    font-size: 16px;
    font-weight: 700;
    line-height: 1.2;
  }

  &__location { color: var(--text-secondary); }

  &__specialties { display: flex; flex-wrap: wrap; gap: 4px; }

  &__rating {
    display: flex;
    align-items: center;
    color: var(--text-secondary);
  }

  &__price { color: var(--text-primary); }
}
</style>
