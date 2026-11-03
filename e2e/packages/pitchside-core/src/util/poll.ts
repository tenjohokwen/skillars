export interface PollOptions {
  /** Total time budget. */
  timeoutMs: number
  /** Delay between attempts. */
  intervalMs?: number
  /** Used in the timeout error, e.g. "mail to a@b.test". A function is evaluated at timeout. */
  describe: string | (() => string)
  /** Injected for tests; defaults to a real timer. */
  sleep?: (ms: number) => Promise<void>
  /** Injected for tests; defaults to Date.now. */
  now?: () => number
}

export class PollTimeoutError extends Error {
  constructor(
    message: string,
    readonly lastError?: unknown,
  ) {
    super(message)
    this.name = 'PollTimeoutError'
  }
}

export const realSleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms))

/**
 * Repeats `attempt` until it returns a non-undefined value or the budget runs out. An attempt that
 * throws is treated as "not yet" (the error is kept and reported on timeout), so a transiently
 * unavailable dependency does not end the wait early.
 */
export async function pollUntil<T>(
  attempt: () => Promise<T | undefined>,
  options: PollOptions,
): Promise<T> {
  const sleep = options.sleep ?? realSleep
  const now = options.now ?? Date.now
  const interval = options.intervalMs ?? 250
  const deadline = now() + options.timeoutMs
  let lastError: unknown
  for (;;) {
    try {
      const value = await attempt()
      if (value !== undefined) return value
    } catch (error) {
      lastError = error
    }
    if (now() >= deadline) {
      const cause = lastError instanceof Error ? ` (last error: ${lastError.message})` : ''
      throw new PollTimeoutError(
        `Timed out after ${options.timeoutMs} ms waiting for ${typeof options.describe === 'function' ? options.describe() : options.describe}${cause}`,
        lastError,
      )
    }
    await sleep(Math.min(interval, Math.max(0, deadline - now())))
  }
}
