<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 320px; max-width: 400px;">
      <q-card-section>
        <div class="text-h6 flex items-center">
          <q-icon name="warning" color="warning" size="sm" class="q-mr-sm" />
          {{ $t('session.expiring') }}
        </div>
      </q-card-section>

      <q-card-section class="q-pt-none">
        <p>{{ $t('session.continueQuestion') }}</p>

        <!-- Visual countdown display -->
        <div class="text-center q-my-lg">
          <div class="countdown-display text-h2 text-weight-bold" :class="countdownClass">
            {{ formattedCountdown }}
          </div>
          <div class="text-caption text-grey-7 q-mt-sm">
            {{ $t('session.expiringDesc', { minutes: minutesRemaining }) }}
          </div>
        </div>

        <q-linear-progress
          :value="progressValue"
          :color="progressColor"
          size="8px"
          rounded
          class="q-mt-md"
        />
      </q-card-section>

      <q-card-actions align="right">
        <q-btn
          flat
          :label="$t('auth.logout')"
          :disable="isRefreshing"
          @click="handleLogout"
        />
        <q-btn
          color="primary"
          :label="$t('session.continueSession')"
          :loading="isRefreshing"
          :disable="isRefreshing"
          @click="handleRefresh"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, watch, computed } from 'vue';
import { useSession } from 'src/composables/useSession';

const {
  showWarning,
  secondsRemaining,
  minutesRemaining,
  isRefreshing,
  handleRefresh,
  handleLogout,
} = useSession();

// Create local writable ref for q-dialog v-model (showWarning is readonly)
const dialogVisible = ref(showWarning.value);

// Keep dialogVisible in sync with showWarning
watch(showWarning, (newVal) => {
  dialogVisible.value = newVal;
});

// Format countdown as MM:SS
const formattedCountdown = computed(() => {
  const totalSeconds = secondsRemaining.value;
  const mins = Math.floor(totalSeconds / 60);
  const secs = totalSeconds % 60;
  return `${mins}:${secs.toString().padStart(2, '0')}`;
});

// Progress value (0-1) based on 2 minutes warning threshold
const progressValue = computed(() => {
  const maxSeconds = 2 * 60; // 2 minutes
  return Math.max(0, Math.min(1, secondsRemaining.value / maxSeconds));
});

// Progress bar color based on time remaining
const progressColor = computed(() => {
  if (secondsRemaining.value <= 30) return 'negative';
  if (secondsRemaining.value <= 60) return 'warning';
  return 'primary';
});

// Countdown text class for urgency
const countdownClass = computed(() => {
  if (secondsRemaining.value <= 30) return 'text-negative';
  if (secondsRemaining.value <= 60) return 'text-warning';
  return 'text-primary';
});
</script>

<style scoped>
.countdown-display {
  font-family: 'Roboto Mono', monospace;
  letter-spacing: 2px;
}
</style>
