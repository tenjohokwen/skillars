import { randomBytes } from 'node:crypto'

export interface UniqueNamesOptions {
  /** Domain for generated email addresses. Use a reserved test domain, e.g. "example.test". */
  emailDomain: string
  /** Identifies one run; defaults to a random 6-hex token. Pass the CI run id to correlate logs. */
  runId?: string
  /**
   * Distinguishes this instance from every other with the same run id and worker index — a second
   * Playwright invocation in the same CI job (e.g. a serial lane) restarts worker indexes and
   * counters at 0. Random 4-hex by default; inject a fixed value only in unit tests.
   */
  nonce?: string
}

/**
 * Unique, human-readable names per worker and run. Unique emails matter beyond tidiness: login
 * and resend limiters are often keyed on the address, so two tests sharing one account can trip a
 * lockout that neither caused alone.
 */
export class UniqueNames {
  private counter = 0
  readonly runId: string
  readonly nonce: string

  constructor(
    readonly workerIndex: number,
    private readonly options: UniqueNamesOptions,
  ) {
    if (!/^[a-z0-9.-]+\.[a-z]{2,}$/i.test(options.emailDomain)) {
      throw new Error(`emailDomain "${options.emailDomain}" is not a domain`)
    }
    this.runId = (options.runId ?? randomBytes(3).toString('hex'))
      .replace(/[^a-z0-9]/gi, '')
      .toLowerCase()
    if (!this.runId) throw new Error('runId must contain at least one alphanumeric character')
    this.nonce = (options.nonce ?? randomBytes(2).toString('hex'))
      .replace(/[^a-z0-9]/gi, '')
      .toLowerCase()
  }

  /** e.g. `coach-r3f9a1c0de-w2-7`: prefix, run id + nonce, worker, counter. */
  token(prefix: string): string {
    const safePrefix = prefix
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '')
    this.counter += 1
    return `${safePrefix || 'x'}-r${this.runId}${this.nonce}-w${this.workerIndex}-${this.counter}`
  }

  email(prefix: string): string {
    return `${this.token(prefix)}@${this.options.emailDomain}`.toLowerCase()
  }

  /** A display name with only letters and spaces, safe for name-field validators. */
  personName(first: string): string {
    this.counter += 1
    const suffix = toLetters(this.workerIndex * 10_000 + this.counter)
    return `${first} ${suffix.charAt(0).toUpperCase()}${suffix.slice(1)}`
  }
}

/** 0 → "a", 25 → "z", 26 → "ba" … — base-26 with letters only. */
function toLetters(value: number): string {
  let remaining = value
  let out = ''
  do {
    out = String.fromCharCode(97 + (remaining % 26)) + out
    remaining = Math.floor(remaining / 26)
  } while (remaining > 0)
  return out
}
