import type { Page } from '@playwright/test'
import type { MailSink, SuiteEnv } from 'pitchside-core'
import { expect, scenario, test } from '../../src/app.js'
import { VERIFY_TOKEN, type Provisioner } from '../../src/data/provisioner.js'
import { R, REGISTRATION } from '../../src/routes.js'
import { LoginScreen, PhoneVerifyScreen } from '../../src/screens/index.js'

// B · Registration, backfill: the phone-OTP step. It is off in every environment while no SMS gateway exists,
// and the admin config API refuses to turn it on; the code is still generated and emailed (C-7). Owner decision
// 2026-11-09: cover the step by forcing the flag by SQL in serial @config tests, restored after each one.

const PHONE_OTP = 'security.registration.phone-otp-required'
const OTP = /\b(\d{6})\b/

let previous: string | undefined
test.beforeEach(async ({ provision }) => {
  previous = await provision.forceConfigValue(PHONE_OTP, 'true')
})
test.afterEach(async ({ provision }) => {
  if (previous !== undefined) await provision.forceConfigValue(PHONE_OTP, previous)
  previous = undefined
})

/**
 * A parent registers (API) and follows the emailed verification link in the page, which, with the flag on, leads
 * to the phone step. Returns the account and the code emailed in its place of an SMS.
 */
async function toPhoneStep(
  page: Page,
  suite: SuiteEnv,
  mail: MailSink,
  provision: Provisioner,
): Promise<{
  account: Awaited<ReturnType<Provisioner['unverified']>>
  code: string
  phone: PhoneVerifyScreen
}> {
  const registeredAt = new Date(Date.now() - 2_000)
  const account = await provision.unverified('parent')
  const verify = await mail.waitForMessage({
    to: account.email,
    subjectMatches: /verify your/i,
    since: registeredAt,
    timeoutMs: 30_000,
  })
  const verifiedAt = new Date(Date.now() - 1_000)
  await page.goto(
    suite.routing.toUrl(`${REGISTRATION.parent.verifyEmail}?token=${verify.code(VERIFY_TOKEN)}`),
  )
  const phone = await new PhoneVerifyScreen(page, suite, 'parent').waitReady()
  return { account, code: await otpSince(mail, account.email, verifiedAt), phone }
}

async function otpSince(mail: MailSink, to: string, since: Date): Promise<string> {
  const message = await mail.waitForMessage({
    to,
    subjectMatches: /verification code/i,
    since,
    timeoutMs: 30_000,
  })
  return message.code(OTP)
}

/** Where a completed phone step goes: the create-player page, which first asks a logged-out parent to log in. */
const leftPhoneStep = (route: string) => route !== REGISTRATION.parent.verifyPhone

test.describe('B · Registration — the phone code', () => {
  test(
    'each OTP box advances to the next as a digit is typed, and the sixth digit submits by itself',
    scenario({
      id: 'REG-14',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@registration', '@config', '@slow'],
    }),
    async ({ page, suite, mail, provision }) => {
      test.slow()
      const { code, phone } = await toPhoneStep(page, suite, mail, provision)
      await phone.digits().first().click()
      for (let i = 0; i < 5; i++) {
        await page.keyboard.type(code[i]!)
        await expect(phone.digits().nth(i + 1)).toBeFocused()
      }
      await page.keyboard.type(code[5]!)
      // No button: the sixth digit sends it.
      await expect.poll(() => leftPhoneStep(phone.currentRoute())).toBe(true)
    },
  )

  test(
    'pasting the six-digit code fills every box and submits',
    scenario({
      id: 'REG-15',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@registration', '@config', '@slow'],
    }),
    async ({ page, suite, mail, provision }) => {
      test.slow()
      const { code, phone } = await toPhoneStep(page, suite, mail, provision)
      // As pasted from a mail client: surrounding spaces and all.
      await phone
        .digits()
        .first()
        .evaluate((input, text) => {
          const data = new DataTransfer()
          data.setData('text/plain', text)
          input.dispatchEvent(
            new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }),
          )
        }, ` ${code} `)
      await expect.poll(() => leftPhoneStep(phone.currentRoute())).toBe(true)
    },
  )

  test(
    'a wrong code shows the vague mismatch error and clears the boxes for another try',
    scenario({
      id: 'REG-16',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@registration', '@config', '@slow'],
    }),
    async ({ page, suite, mail, provision, consoleGate }) => {
      test.slow()
      consoleGate.allow('status of 400', '[Server Error]', 'status code 400')
      const { code, phone } = await toPhoneStep(page, suite, mail, provision)
      const wrong = code === '000000' ? '111111' : '000000'
      await phone.typeCode(wrong)
      await expect(phone.error()).toBeVisible()
      // The same error for every cause (wrong, expired, too many tries): it says nothing about which.
      await expect(phone.error()).not.toContainText(code)
      for (let i = 0; i < 6; i++) await expect(phone.digits().nth(i)).toHaveValue('')
      await expect(phone.digits().first()).toBeFocused()
      expect(phone.currentRoute()).toBe(REGISTRATION.parent.verifyPhone)

      // The right code still works afterwards.
      await phone.typeCode(code)
      await expect.poll(() => leftPhoneStep(phone.currentRoute())).toBe(true)
    },
  )

  test(
    'resending the code makes the earlier one stop working',
    scenario({
      id: 'REG-17',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@registration', '@config', '@slow'],
    }),
    async ({ page, suite, mail, provision, consoleGate }) => {
      test.slow()
      consoleGate.allow('status of 400', '[Server Error]', 'status code 400')
      const { account, code: first, phone } = await toPhoneStep(page, suite, mail, provision)
      const resentAt = new Date(Date.now() - 1_000)
      await phone.resend().click()
      const second = await otpSince(mail, account.email, resentAt)
      if (second === first) test.skip(true, 'the new code happened to repeat the old one')

      await phone.typeCode(first)
      await expect(phone.error()).toBeVisible()
      expect(phone.currentRoute()).toBe(REGISTRATION.parent.verifyPhone)
      await phone.typeCode(second)
      await expect.poll(() => leftPhoneStep(phone.currentRoute())).toBe(true)
    },
  )

  test(
    'with the phone step required, login is refused until the code is entered, then allowed',
    scenario({
      id: 'REG-21',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@parent', '@registration', '@config', '@slow'],
    }),
    async ({ page, browser, suite, mail, provision, consoleGate }) => {
      test.slow()
      consoleGate.allow('status of 403', 'status of 401')
      const { account, code, phone } = await toPhoneStep(page, suite, mail, provision)

      // Email verified, phone not: a login in another browser is refused as not verified.
      const other = await browser.newContext({ baseURL: suite.baseUrl })
      try {
        const otherPage = await other.newPage()
        const login = await new LoginScreen(otherPage, suite).open()
        await login.logIn(account.email, account.password)
        await expect(login.notVerifiedBanner()).toBeVisible()
        expect(login.currentRoute()).toBe(R.login)

        await phone.typeCode(code)
        await expect.poll(() => leftPhoneStep(phone.currentRoute())).toBe(true)

        await login.logIn(account.email, account.password)
        await expect.poll(() => login.currentRoute()).not.toBe(R.login)
      } finally {
        await other.close()
      }
    },
  )
})
