<template>
  <q-page>
    <div class="app-page fade-in">

      <!-- Page header -->
      <div class="page-header q-mb-xl">
        <div class="text-page-title">{{ t('profile.title') }}</div>
        <div class="text-meta">Manage your account details and preferences.</div>
      </div>

      <!-- Loading -->
      <div v-if="isLoading" class="flex flex-center q-py-xl">
        <q-spinner-dots size="40px" style="color: var(--accent-primary)" />
      </div>

      <!-- Error -->
      <div v-else-if="hasError && !profile" class="glass-card--static q-pa-lg">
        <div class="text-meta" style="color: var(--accent-danger)">
          {{ errorMessage }}
          <template v-if="helpCode">
            <br /><small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
          </template>
        </div>
      </div>

      <!-- Profile sections -->
      <template v-else-if="profile">
        <div class="profile-grid">

          <div class="glass-card profile-section">
            <div class="text-label q-mb-lg">Account</div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.email') }}</div>
                <div class="text-body q-mt-xs">{{ profile.email }}</div>
              </div>
              <q-btn flat dense icon="edit" round class="edit-btn" @click="showEmailDialog = true">
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.password') }}</div>
                <div class="text-body q-mt-xs">{{ t('profile.passwordMasked') }}</div>
              </div>
              <q-btn flat dense icon="edit" round class="edit-btn" @click="showPasswordDialog = true">
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.twoFactorAuth') }}</div>
                <div class="q-mt-xs">
                  <span class="badge-accent" :style="profile.otpEnabled ? '' : 'opacity: 0.5'">
                    {{ profile.otpEnabled ? t('profile.enabled') : t('profile.disabled') }}
                  </span>
                </div>
              </div>
              <q-btn flat dense icon="edit" round class="edit-btn" @click="show2faDialog = true">
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>
          </div>

          <div class="glass-card profile-section">
            <div class="text-label q-mb-lg">Personal Info</div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.personalInfo') }}</div>
                <div class="text-body q-mt-xs">{{ fullName || '—' }}</div>
                <div class="text-meta q-mt-xs" v-if="profile.nationalId">
                  {{ t('auth.nationalId') }}: {{ profile.nationalId }}
                </div>
                <div class="text-meta q-mt-xs" v-if="profile.gender">
                  {{ t('auth.gender') }}: {{ genderLabel }}
                </div>
                <div class="text-meta q-mt-xs" v-if="profile.langKey">
                  {{ t('auth.preferredLanguage') }}: {{ languageLabel }}
                </div>
              </div>
              <q-btn flat dense icon="edit" round class="edit-btn" @click="showInfoDialog = true">
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.phone') }}</div>
                <div class="text-body q-mt-xs">{{ profile.phone || t('profile.noPhone') }}</div>
              </div>
              <q-btn flat dense icon="edit" round class="edit-btn" @click="showPhoneDialog = true">
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.address') }}</div>
                <div class="text-body q-mt-xs">{{ formattedAddress || t('profile.noAddress') }}</div>
              </div>
              <q-btn flat dense icon="edit" round class="edit-btn" @click="showAddressDialog = true">
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>
          </div>

        </div>
      </template>

    </div>

    <!-- Dialogs -->
    <UpdateEmailDialog v-model="showEmailDialog" :current-email="profile?.email" @updated="loadProfile" />
    <UpdatePasswordDialog v-model="showPasswordDialog" @updated="loadProfile" />
    <UpdatePhoneDialog v-model="showPhoneDialog" :current-phone="profile?.phone" @updated="loadProfile" />
    <UpdateAddressDialog v-model="showAddressDialog" :current-address="profile?.address" @updated="loadProfile" />
    <UpdateInfoDialog v-model="showInfoDialog" :current-info="currentInfo" @updated="loadProfile" />
    <Toggle2faDialog v-model="show2faDialog" :current-enabled="profile?.otpEnabled" @updated="loadProfile" />
  </q-page>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { profileApi } from 'src/api/profile.api';
import { useErrorHandler } from 'src/composables/useErrorHandler';
import UpdateEmailDialog from 'src/components/profile/UpdateEmailDialog.vue';
import UpdatePasswordDialog from 'src/components/profile/UpdatePasswordDialog.vue';
import UpdatePhoneDialog from 'src/components/profile/UpdatePhoneDialog.vue';
import UpdateAddressDialog from 'src/components/profile/UpdateAddressDialog.vue';
import UpdateInfoDialog from 'src/components/profile/UpdateInfoDialog.vue';
import Toggle2faDialog from 'src/components/profile/Toggle2faDialog.vue';

const { t } = useI18n();
const { setError, clearError, hasError, errorMessage, helpCode } = useErrorHandler();

const profile = ref(null);
const isLoading = ref(false);

const showEmailDialog = ref(false);
const showPasswordDialog = ref(false);
const showPhoneDialog = ref(false);
const showAddressDialog = ref(false);
const showInfoDialog = ref(false);
const show2faDialog = ref(false);

onMounted(async () => { await loadProfile(); });

async function loadProfile() {
  isLoading.value = true;
  clearError();
  try {
    profile.value = await profileApi.getProfile();
  } catch (err) {
    setError(err);
  } finally {
    isLoading.value = false;
  }
}

const formattedAddress = computed(() => {
  if (!profile.value?.address) return null;
  const a = profile.value.address;
  const lines = [a.addressLine1, a.addressLine2, a.addressLine3].filter(Boolean);
  const parts = [lines.join(', '), a.city, a.stateProvince, a.postalCode, a.country].filter(Boolean);
  return parts.join(', ');
});

const fullName = computed(() => {
  if (!profile.value) return '';
  return [profile.value.title, profile.value.firstName, profile.value.lastName].filter(Boolean).join(' ');
});

const genderLabel = computed(() => {
  const m = { MALE: t('auth.male'), FEMALE: t('auth.female'), OTHER: t('auth.other') };
  return m[profile.value?.gender] || profile.value?.gender || '';
});

const languageLabel = computed(() => {
  const m = { en: t('auth.languageEnglish'), fr: t('auth.languageFrench') };
  return m[profile.value?.langKey] || profile.value?.langKey || '';
});

const currentInfo = computed(() => {
  if (!profile.value) return null;
  return {
    title: profile.value.title, firstName: profile.value.firstName,
    lastName: profile.value.lastName, nationalId: profile.value.nationalId,
    gender: profile.value.gender, langKey: profile.value.langKey,
  };
});
</script>

<style lang="scss" scoped>
.page-header {
  border-bottom: 1px solid var(--border-soft);
  padding-bottom: 24px;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 24px;
}

.profile-section {
  padding: 28px;
}

.profile-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 16px 0;
  border-bottom: 1px solid var(--border-soft);

  &:last-child {
    border-bottom: none;
    padding-bottom: 0;
  }

  &:first-of-type {
    padding-top: 0;
  }
}

.edit-btn {
  color: var(--text-muted) !important;
  flex-shrink: 0;
  margin-left: 12px;

  &:hover {
    color: var(--accent-primary) !important;
    background: var(--nav-active-bg) !important;
  }
}
</style>
