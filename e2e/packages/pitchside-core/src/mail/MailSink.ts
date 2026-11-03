export interface MailQuery {
  /** Recipient address (case-insensitive). */
  to: string
  subjectMatches?: RegExp
  /** Only messages received at or after this instant. */
  since?: Date
  /** Default 10 000 ms. */
  timeoutMs?: number
  /** Default 250 ms. */
  pollIntervalMs?: number
}

export interface MailMessage {
  readonly id: string
  readonly to: readonly string[]
  readonly from?: string
  readonly subject: string
  readonly text: string
  readonly html: string
  readonly receivedAt: Date
  /** Every distinct http(s) link in the HTML hrefs and the text body, in order of appearance. */
  links(): string[]
  /** The first match of `pattern` (default: a standalone 6-digit code, e.g. an OTP). Throws if none. */
  code(pattern?: RegExp): string
}

/**
 * A search, not a mailbox dump: "the newest message to this address matching this subject within
 * N seconds", returned parsed with its links and codes extracted.
 */
export interface MailSink {
  waitForMessage(query: MailQuery): Promise<MailMessage>
  /** Delete everything, or only messages to one address. */
  purge(to?: string): Promise<void>
}

export interface RawMail {
  id: string
  to: readonly string[]
  from?: string
  subject: string
  text?: string
  html?: string
  receivedAt: Date
}

export const DEFAULT_CODE_PATTERN = /\b\d{6}\b/

const decodeEntities = (value: string): string =>
  value
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, code: string) => String.fromCodePoint(Number(code)))

/** Crude but adequate for assertions: drops tags, scripts and styles, decodes entities. */
export function htmlToText(html: string): string {
  return decodeEntities(
    html
      .replace(/<(script|style)[^>]*>[\s\S]*?<\/\1>/gi, ' ')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<\/(p|div|tr|li|h[1-6])>/gi, '\n')
      .replace(/<[^>]+>/g, ' '),
  )
    .replace(/[ \t]+/g, ' ')
    .replace(/\n\s+/g, '\n')
    .trim()
}

export function extractLinks(html: string, text: string): string[] {
  const found: string[] = []
  for (const match of html.matchAll(/href\s*=\s*(["'])(.*?)\1/gi)) {
    found.push(decodeEntities(match[2] ?? ''))
  }
  for (const match of text.matchAll(/https?:\/\/[^\s<>"')\]]+/gi)) found.push(match[0])
  return [...new Set(found.filter((link) => /^https?:\/\//i.test(link)))]
}

export function toMailMessage(raw: RawMail): MailMessage {
  const html = raw.html ?? ''
  const text = raw.text && raw.text.trim() ? raw.text : htmlToText(html)
  return {
    id: raw.id,
    to: raw.to.map((address) => address.toLowerCase()),
    from: raw.from,
    subject: raw.subject,
    text,
    html,
    receivedAt: raw.receivedAt,
    links: () => extractLinks(html, text),
    code(pattern = DEFAULT_CODE_PATTERN) {
      // Text first, then raw HTML, so a token that only appears inside a link href is found too.
      const match =
        pattern.exec(text) ?? pattern.exec(htmlToText(html)) ?? pattern.exec(decodeEntities(html))
      if (!match) {
        throw new Error(
          `No code matching ${pattern} in mail "${raw.subject}" to ${raw.to.join(', ')}`,
        )
      }
      return match[1] ?? match[0]
    },
  }
}

/** Shared matching rule for every sink. */
export function matchesQuery(message: RawMail, query: MailQuery): boolean {
  const to = query.to.toLowerCase()
  if (!message.to.some((address) => address.toLowerCase() === to)) return false
  if (query.subjectMatches && !query.subjectMatches.test(message.subject)) return false
  if (query.since && message.receivedAt.getTime() < query.since.getTime()) return false
  return true
}

/** Newest first. */
export function newest<T extends { receivedAt: Date }>(items: readonly T[]): T | undefined {
  return [...items].sort((a, b) => b.receivedAt.getTime() - a.receivedAt.getTime())[0]
}
