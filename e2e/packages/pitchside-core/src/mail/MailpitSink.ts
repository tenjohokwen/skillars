import { pollUntil } from '../util/poll.js'
import {
  matchesQuery,
  newest,
  toMailMessage,
  type MailMessage,
  type MailQuery,
  type MailSink,
  type RawMail,
} from './MailSink.js'

type FetchFn = typeof fetch

export interface MailpitSinkOptions {
  /** Mailpit web/API root, e.g. http://localhost:8025 */
  baseUrl: string
  fetch?: FetchFn
}

interface MailpitAddress {
  Address: string
  Name?: string
}
interface MailpitSummary {
  ID: string
  From?: MailpitAddress
  To?: MailpitAddress[]
  Subject: string
  Created: string
}
interface MailpitMessage extends MailpitSummary {
  Text?: string
  HTML?: string
}

/** MailSink over Mailpit's REST API (`/api/v1`). */
export class MailpitSink implements MailSink {
  private readonly fetchFn: FetchFn
  private readonly base: string

  constructor(options: MailpitSinkOptions) {
    this.fetchFn = options.fetch ?? fetch
    this.base = options.baseUrl.replace(/\/+$/, '')
  }

  async waitForMessage(query: MailQuery): Promise<MailMessage> {
    const summary = await pollUntil(
      async () => {
        const candidates = (await this.search(query.to)).filter((raw) => matchesQuery(raw, query))
        return newest(candidates)
      },
      {
        timeoutMs: query.timeoutMs ?? 10_000,
        intervalMs: query.pollIntervalMs ?? 250,
        describe: `mail to ${query.to}${query.subjectMatches ? ` matching ${query.subjectMatches}` : ''}`,
      },
    )
    const full = await this.getJson<MailpitMessage>(
      `/api/v1/message/${encodeURIComponent(summary.id)}`,
    )
    return toMailMessage({ ...summary, text: full.Text ?? '', html: full.HTML ?? '' })
  }

  async purge(to?: string): Promise<void> {
    const path = to
      ? `/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}`
      : '/api/v1/messages'
    const response = await this.fetchFn(`${this.base}${path}`, { method: 'DELETE' })
    if (!response.ok) throw new Error(`Mailpit purge failed: HTTP ${response.status}`)
  }

  private async search(to: string): Promise<RawMail[]> {
    const query = encodeURIComponent(`to:"${to}"`)
    const result = await this.getJson<{ messages?: MailpitSummary[] }>(
      `/api/v1/search?query=${query}&limit=50`,
    )
    return (result.messages ?? []).map((message) => ({
      id: message.ID,
      to: (message.To ?? []).map((address) => address.Address),
      from: message.From?.Address,
      subject: message.Subject,
      receivedAt: new Date(message.Created),
    }))
  }

  private async getJson<T>(path: string): Promise<T> {
    const response = await this.fetchFn(`${this.base}${path}`, {
      headers: { accept: 'application/json' },
    })
    if (!response.ok) throw new Error(`Mailpit GET ${path} failed: HTTP ${response.status}`)
    return (await response.json()) as T
  }
}
