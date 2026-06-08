<template>
  <q-page class="flex flex-center">
    <div class="q-pa-md" style="width: 100%; max-width: 900px;">
      <q-card>
        <q-card-section>
          <div class="text-h5 text-center">{{ t('profile.title') }}</div>
        </q-card-section>

        <q-card-section v-if="isLoading" class="flex flex-center">
          <q-spinner-dots color="primary" size="40px" />
        </q-card-section>

        <q-card-section v-else-if="hasError && !profile">
          <q-banner class="bg-negative text-white" rounded>
            {{ errorMessage }}
            <template v-if="helpCode">
              <br />
              <small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>
        </q-card-section>

        <template v-else-if="profile">
          <q-list separator>
            <!-- Email Section -->
            <q-item>
              <q-item-section>
                <q-item-label overline>{{ t('profile.email') }}</q-item-label>
                <q-item-label>{{ profile.email }}</q-item-label>
              </q-item-section>
              <q-item-section side>
                <q-btn
                  flat
                  dense
                  icon="edit"
                  :label="t('profile.edit')"
                  @click="showEmailDialog = true"
                />
              </q-item-section>
            </q-item>

            <!-- Password Section -->
            <q-item>
              <q-item-section>
                <q-item-label overline>{{ t('profile.password') }}</q-item-label>
                <q-item-label>{{ t('profile.passwordMasked') }}</q-item-label>
              </q-item-section>
              <q-item-section side>
                <q-btn
                  flat
                  dense
                  icon="edit"
                  :label="t('profile.edit')"
                  @click="showPasswordDialog = true"
                />
              </q-item-section>
            </q-item>

            <!-- Phone Section -->
            <q-item>
              <q-item-section>
                <q-item-label overline>{{ t('profile.phone') }}</q-item-label>
                <q-item-label>{{ profile.phone || t('profile.noPhone') }}</q-item-label>
              </q-item-section>
              <q-item-section side>
                <q-btn
                  flat
                  dense
                  icon="edit"
                  :label="t('profile.edit')"
                  @click="showPhoneDialog = true"
                />
              </q-item-section>
            </q-item>

            <!-- Address Section -->
            <q-item>
              <q-item-section>
                <q-item-label overline>{{ t('profile.address') }}</q-item-label>
                <q-item-label>{{ formattedAddress || t('profile.noAddress') }}</q-item-label>
              </q-item-section>
              <q-item-section side>
                <q-btn
                  flat
                  dense
                  icon="edit"
                  :label="t('profile.edit')"
                  @click="showAddressDialog = true"
                />
              </q-item-section>
            </q-item>

            <!-- Personal Information Section -->
            <q-item>
              <q-item-section>
                <q-item-label overline>{{ t('profile.personalInfo') }}</q-item-label>
                <q-item-label v-if="fullName">{{ fullName }}</q-item-label>
                <q-item-label caption v-if="profile.nationalId">
                  {{ t('auth.nationalId') }}: {{ profile.nationalId }}
                </q-item-label>
                <q-item-label caption v-if="profile.gender">
                  {{ t('auth.gender') }}: {{ genderLabel }}
                </q-item-label>
                <q-item-label caption v-if="profile.langKey">
                  {{ t('auth.preferredLanguage') }}: {{ languageLabel }}
                </q-item-label>
              </q-item-section>
              <q-item-section side>
                <q-btn
                  flat
                  dense
                  icon="edit"
                  :label="t('profile.edit')"
                  @click="showInfoDialog = true"
                />
              </q-item-section>
            </q-item>

            <!-- 2FA Section -->
            <q-item>
              <q-item-section>
                <q-item-label overline>{{ t('profile.twoFactorAuth') }}</q-item-label>
                <q-item-label>
                  <q-badge
                    :color="profile.otpEnabled ? 'positive' : 'grey'"
                    :label="profile.otpEnabled ? t('profile.enabled') : t('profile.disabled')"
                  />
                </q-item-label>
              </q-item-section>
              <q-item-section side>
                <q-btn
                  flat
                  dense
                  icon="edit"
                  :label="t('profile.edit')"
                  @click="show2faDialog = true"
                />
              </q-item-section>
            </q-item>
          </q-list>
        </template>
      </q-card>
    </div>

    <!-- Update Dialogs -->
    <UpdateEmailDialog
      v-model="showEmailDialog"
      :current-email="profile?.email"
      @updated="loadProfile"
    />
    <UpdatePasswordDialog
      v-model="showPasswordDialog"
      @updated="loadProfile"
    />
    <UpdatePhoneDialog
      v-model="showPhoneDialog"
      :current-phone="profile?.phone"
      @updated="loadProfile"
    />
    <UpdateAddressDialog
      v-model="showAddressDialog"
      :current-address="profile?.address"
      @updated="loadProfile"
    />
    <UpdateInfoDialog
      v-model="showInfoDialog"
      :current-info="currentInfo"
      @updated="loadProfile"
    />
    <Toggle2faDialog
      v-model="show2faDialog"
      :current-enabled="profile?.otpEnabled"
      @updated="loadProfile"
    />
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

// Dialog visibility refs (will be used when dialogs are added in plan 03/04)
const showEmailDialog = ref(false);
const showPasswordDialog = ref(false);
const showPhoneDialog = ref(false);
const showAddressDialog = ref(false);
const showInfoDialog = ref(false);
const show2faDialog = ref(false);

onMounted(async () => {
  await loadProfile();
});

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

// Computed for formatted address display
const formattedAddress = computed(() => {
  if (!profile.value?.address) return null;
  const a = profile.value.address;
  const lines = [a.addressLine1, a.addressLine2, a.addressLine3].filter(Boolean);
  const parts = [lines.join(', '), a.city, a.stateProvince, a.postalCode, a.country].filter(Boolean);
  return parts.join(', ');
});

// Computed for formatted name display
const fullName = computed(() => {
  if (!profile.value) return '';
  const parts = [profile.value.title, profile.value.firstName, profile.value.lastName].filter(Boolean);
  return parts.join(' ');
});

// Computed for gender label
const genderLabel = computed(() => {
  if (!profile.value?.gender) return '';
  const genderMap = {
    MALE: t('auth.male'),
    FEMALE: t('auth.female'),
    OTHER: t('auth.other')
  };
  return genderMap[profile.value.gender] || profile.value.gender;
});

// Computed for language label
const languageLabel = computed(() => {
  if (!profile.value?.langKey) return '';
  const langMap = {
    en: t('auth.languageEnglish'),
    fr: t('auth.languageFrench')
  };
  return langMap[profile.value.langKey] || profile.value.langKey;
});

// Computed for currentInfo (to pass to UpdateInfoDialog)
const currentInfo = computed(() => {
  if (!profile.value) return null;
  return {
    title: profile.value.title,
    firstName: profile.value.firstName,
    lastName: profile.value.lastName,
    nationalId: profile.value.nationalId,
    gender: profile.value.gender,
    langKey: profile.value.langKey
  };
});
</script>
