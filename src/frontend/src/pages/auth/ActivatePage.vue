<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">

      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
        <div class="text-meta">Account activation</div>
      </div>

      <div class="glass-card--static auth-card text-center">

        <div class="text-section-title q-mb-xs">{{ t('auth.activateAccount') }}</div>

        <!-- Loading -->
        <div v-if="isLoading" class="q-py-xl">
          <q-spinner-dots size="40px" style="color: var(--accent-primary)" />
          <div class="text-meta q-mt-md">{{ t('common.loading') }}</div>
        </div>

        <!-- No key -->
        <div v-else-if="!activationKey" class="auth-banner auth-banner--error q-pa-md q-mt-md">
          Invalid or missing activation key.
        </div>

        <!-- Success -->
        <div v-else-if="isSuccess" class="q-py-md">
          <q-icon name="check_circle" size="48px" style="color: var(--accent-primary)" class="q-mb-md" />
          <div class="text-card-title q-mb-sm">{{ t('success.activated') }}</div>
          <div class="text-meta">Redirecting in {{ countdown }}...</div>
        </div>

        <!-- Error -->
        <template v-else-if="hasError">
          <div class="auth-banner auth-banner--error q-pa-md q-mt-md">
            {{ errorMessage }}
            <template v-if="helpCode">
              <br /><small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </div>
          <div class="q-mt-md">
            <router-link to="/login" class="auth-link">
              &larr; {{ t('common.back') }} {{ t('auth.login') }}
            </router-link>
          </div>
        </template>

      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { accountApi } from 'src/api/account.api';
import { useErrorHandler } from 'src/composables/useErrorHandler';

const router = useRouter();
const route = useRoute();
const { t } = useI18n();
const { setError, hasError, errorMessage, helpCode } = useErrorHandler();

const activationKey = computed(() => route.query.key);
const isLoading = ref(true);
const isSuccess = ref(false);
const countdown = ref(3);
let redirectTimer = null;

async function handleActivation() {
  if (!activationKey.value) { isLoading.value = false; return; }
  try {
    await accountApi.activate(activationKey.value);
    isSuccess.value = true;
    isLoading.value = false;
    redirectTimer = setInterval(() => {
      countdown.value--;
      if (countdown.value <= 0) { clearInterval(redirectTimer); router.push('/login'); }
    }, 1000);
  } catch (err) {
    setError(err);
    isLoading.value = false;
  }
}

onMounted(() => { handleActivation(); });
onUnmounted(() => { if (redirectTimer) clearInterval(redirectTimer); });
</script>

<style lang="scss" scoped>
.auth-brand { text-align: center; }
.auth-brand-name {
  font-size: 32px;
  font-weight: 800;
  font-family: 'Inter', sans-serif;
  letter-spacing: -1px;
}
.auth-card { padding: 32px; }
.auth-link {
  color: var(--accent-primary);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  &:hover { opacity: 0.8; }
}
.auth-banner {
  border-radius: 12px !important;
  font-size: 14px;
  &--error { background: rgba(255, 95, 122, 0.12); color: var(--accent-danger); }
}
</style>
