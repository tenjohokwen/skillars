// skillars-deferred-140 AC3.3 — CoachProfileBuilderPlaceholderPage.vue's error banner.
//
// Before this story, the banner read `store.error?.response?.data?.message`, a field the real
// ErrorDto response never has (the message is nested under `errorMsg.message` — see
// infrastructure/message/ErrorDto.java) — so it rendered the generic fallback for EVERY error, in
// EVERY locale, with no key-based translation lookup at all. This spec mounts with the REAL i18n
// message catalogs (not the test-suite's usual key-passthrough stub) so a false pass is impossible:
// if the banner fell back to `error.generic` it would render that literal string, not the
// `marketplace.stepOutOfOrder` translation, in every locale below.
//
// `store.currentStep` is set to a value outside 1-5 so the v-if/else-if chain renders none of the
// five step components — the banner's own logic is what is under test, not any step's internals.

import { describe, it, expect, afterEach } from 'vitest'
import { mount, config } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'
import messages from 'src/i18n'
import CoachProfileBuilderPlaceholderPage from 'src/pages/auth/CoachProfileBuilderPlaceholderPage.vue'

// test/vitest/setup-file.js installs a global key-passthrough i18n stub for every spec. This file
// needs the REAL message catalogs instead (that is the whole point of the regression guard), so
// drop the shared stub here — scoped to this file only, since Vitest gives each spec file its own
// module registry and setupFiles rerun fresh per file.
config.global.plugins = []

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', component: { template: '<div/>' } }],
})

function stepOutOfOrderError() {
  return {
    response: {
      data: {
        errorMsg: {
          errorKey: 'marketplace.stepOutOfOrder',
          message: 'Complete Step 1 before submitting Step 2',
        },
      },
    },
  }
}

const mounted = []
function mountPage(locale, error) {
  const i18n = createI18n({
    legacy: false,
    globalInjection: true,
    locale,
    fallbackLocale: 'en-US',
    messages,
  })
  const pinia = createTestingPinia({
    createSpy: () => () => {},
    initialState: { profileBuilder: { currentStep: 99, error } },
  })
  const wrapper = mount(CoachProfileBuilderPlaceholderPage, {
    global: { plugins: [i18n, pinia, router] },
  })
  mounted.push(wrapper)
  return wrapper
}

describe("CoachProfileBuilderPlaceholderPage.vue's error banner (skillars-deferred-140 AC3.3)", () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it('en-US: a key with a translation renders the translated string', () => {
    const wrapper = mountPage('en-US', stepOutOfOrderError())
    expect(wrapper.text()).toContain('Please complete the previous step first.')
    expect(wrapper.text()).not.toContain('Complete Step 1 before submitting Step 2')
  })

  it('fr-FR: renders the French translation, not raw English and not the generic fallback', () => {
    const wrapper = mountPage('fr-FR', stepOutOfOrderError())
    expect(wrapper.text()).toContain("Veuillez d'abord terminer l'étape précédente.")
    expect(wrapper.text()).not.toContain('Complete Step 1 before submitting Step 2')
    expect(wrapper.text()).not.toContain(messages['fr-FR'].error.generic)
  })

  it('de-DE: renders the German translation, not raw English and not the generic fallback', () => {
    const wrapper = mountPage('de-DE', stepOutOfOrderError())
    expect(wrapper.text()).toContain('Bitte schließen Sie zuerst den vorherigen Schritt ab.')
    expect(wrapper.text()).not.toContain('Complete Step 1 before submitting Step 2')
    expect(wrapper.text()).not.toContain(messages['de-DE'].error.generic)
  })

  it('a key with no translation falls back to the raw backend message, not the generic string', () => {
    const wrapper = mountPage('en-US', {
      response: {
        data: { errorMsg: { errorKey: 'marketplace.notARealKey', message: 'Raw backend text' } },
      },
    })
    expect(wrapper.text()).toContain('Raw backend text')
  })

  it('no error present renders no banner at all', () => {
    const wrapper = mountPage('en-US', null)
    expect(wrapper.text()).not.toContain('Please complete the previous step first.')
  })
})
