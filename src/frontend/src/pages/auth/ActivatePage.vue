<template>
  <q-page class="flex flex-center q-pa-md">
    <div class="col-12 col-sm-8 col-md-6 col-lg-4" style="max-width: 400px">
      <q-card>
        <q-card-section>
          <div class="text-h5 text-center">{{ t('auth.activateAccount') }}</div>
        </q-card-section>

        <q-card-section>
          <!-- Loading state -->
          <div v-if="isLoading" class="text-center q-pa-md">
            <q-spinner-dots color="primary" size="50px" />
            <div class="q-mt-md">{{ t('common.loading') }}</div>
          </div>

          <!-- No key error -->
          <q-banner v-else-if="!activationKey" class="bg-negative text-white" rounded>
            Invalid or missing activation key
          </q-banner>

          <!-- Success state -->
          <q-banner v-else-if="isSuccess" class="bg-positive text-white" rounded>
            {{ t('success.activated') }}
            <div class="q-mt-sm">Redirecting in {{ countdown }}...</div>
          </q-banner>

          <!-- Error state -->
          <template v-else-if="hasError">
            <q-banner class="bg-negative text-white" rounded>
              {{ errorMessage }}
              <template v-if="helpCode">
                <br />
                <small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
              </template>
            </q-banner>
            <div class="text-center q-mt-md">
              <router-link to="/login" class="text-primary">
                {{ t('common.back') }} {{ t('auth.login') }}
              </router-link>
            </div>
          </template>
        </q-card-section>
      </q-card>
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

const {
  setError,
  hasError,
  errorMessage,
  helpCode
} = useErrorHandler();

// Get activation key from route query
const activationKey = computed(() => route.query.key);

// State
const isLoading = ref(true);
const isSuccess = ref(false);
const countdown = ref(3);
let redirectTimer = null;

// Handle activation on mount
async function handleActivation() {
  if (!activationKey.value) {
    isLoading.value = false;
    return;
  }

  try {
    await accountApi.activate(activationKey.value);
    isSuccess.value = true;
    isLoading.value = false;

    // Start countdown timer
    redirectTimer = setInterval(() => {
      countdown.value--;
      if (countdown.value <= 0) {
        clearInterval(redirectTimer);
        router.push('/login');
      }
    }, 1000);
  } catch (err) {
    setError(err);
    isLoading.value = false;
  }
}

onMounted(() => {
  handleActivation();
});

onUnmounted(() => {
  if (redirectTimer) {
    clearInterval(redirectTimer);
  }
});
</script>
