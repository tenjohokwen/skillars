import type { PlaywrightTestConfig, Project } from '@playwright/test'

/**
 * Tags whose tests share global state (a full database reset, runtime configuration) and must run
 * outside the parallel pool. CI runs the serial project in its own invocation with `--workers=1`.
 */
export const SERIAL_LANE_TAGS = ['@destructive', '@config'] as const
const serialPattern = new RegExp(SERIAL_LANE_TAGS.map((tag) => `${tag}(?![a-z0-9-])`).join('|'))

export interface LaneOptions {
  /** Project `use` shared by both lanes (browser, viewport…). */
  use?: Project['use']
  /** Prefix for project names, e.g. the browser: "chromium". */
  namePrefix?: string
}

/** A parallel project and a serial project that runs after it. */
export function laneProjects(options: LaneOptions = {}): Project[] {
  const prefix = options.namePrefix ? `${options.namePrefix}-` : ''
  return [
    { name: `${prefix}parallel`, grepInvert: serialPattern, use: options.use, fullyParallel: true },
    {
      name: `${prefix}serial`,
      grep: serialPattern,
      use: options.use,
      fullyParallel: false,
      dependencies: [`${prefix}parallel`],
    },
  ]
}

type VideoMode = 'off' | 'on' | 'retain-on-failure' | 'on-first-retry'
const VIDEO_MODES: readonly VideoMode[] = ['off', 'on', 'retain-on-failure', 'on-first-retry']

export function videoMode(value: string | undefined): VideoMode {
  if (value === undefined || value === '') return 'retain-on-failure'
  if ((VIDEO_MODES as readonly string[]).includes(value)) return value as VideoMode
  throw new Error(`Invalid PITCHSIDE_VIDEO "${value}": expected ${VIDEO_MODES.join(' | ')}`)
}

/**
 * The run policy every Pitchside suite starts from: traces and video only for the failure you will
 * debug, one retry in CI and none locally (a pass-on-retry is still reported as flaky), no
 * `test.only` in CI.
 */
export function baseRunPolicy(isCi: boolean = Boolean(process.env.CI)): PlaywrightTestConfig {
  return {
    forbidOnly: isCi,
    retries: isCi ? 1 : 0,
    reporter: isCi ? [['blob'], ['github']] : [['list'], ['html', { open: 'never' }]],
    use: {
      trace: 'on-first-retry',
      // PITCHSIDE_VIDEO=off for hosts Playwright ships no ffmpeg for (e.g. older macOS).
      video: videoMode(process.env.PITCHSIDE_VIDEO),
      screenshot: 'only-on-failure',
    },
  }
}
