import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  ApiSessionStrategy,
  defineSuite,
  defineTaxonomy,
  hashRouting,
  LogDirSink,
  Screen,
  withPriming,
} from '../../src/index.js'

// The toy app's own "adapter" — exactly the shape a real app package provides, in a dozen lines.
export const TOY_PORT = Number(process.env.TOY_APP_PORT ?? 4317)
const baseUrl = `http://localhost:${TOY_PORT}`
const here = fileURLToPath(new URL('.', import.meta.url))
export const MAIL_DIR = join(here, '..', '..', 'test-results', 'toy-mail')

export const toy = defineSuite({
  name: 'toy-app',
  baseUrl,
  apiBaseUrl: `${baseUrl}/api`,
  routing: hashRouting(baseUrl),
  auth: withPriming(
    new ApiSessionStrategy({
      apiBaseUrl: `${baseUrl}/api`,
      loginPath: '/login',
      logoutPath: '/logout',
      credentials: (role) => ({ username: role, password: 'secret' }),
    }),
    async () => [{ name: 'fp', value: 'fixed-fingerprint', url: baseUrl }],
  ),
  taxonomy: defineTaxonomy({ persona: ['@guest', '@member', '@admin'], domain: ['@kernel'] }),
  ids: { base: 5_000_000_000n },
  emailDomain: 'example.test',
  mail: new LogDirSink({ dir: MAIL_DIR }),
  a11yBaselineDir: join(here, 'a11y-baselines'),
  prepareContext: async (context) => {
    await context.route('https://widgets.example.invalid/**', (route) =>
      route.fulfill({ contentType: 'text/javascript', body: "window.toyWidget = 'stubbed'" }),
    )
  },
})

export const test = toy.test
export const expect = toy.expect

export class LoginScreen extends Screen {
  readonly route = '/login'
  readonly readyTestId = 'login-form'
  username = () => this.tid('login-username-input')
  password = () => this.tid('login-password-input')
  submit = () => this.tid('login-submit-btn')
  error = () => this.tid('login-error')
}

export class HomeScreen extends Screen {
  readonly route = '/home'
  readonly readyTestId = 'home-title'
  role = () => this.tid('home-role')
  fingerprint = () => this.tid('home-fingerprint')
}
