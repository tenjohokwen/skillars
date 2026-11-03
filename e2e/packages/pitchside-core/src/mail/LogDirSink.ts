import { readdir, readFile, rm, stat } from 'node:fs/promises'
import { join } from 'node:path'
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

export interface DumpedFile {
  name: string
  content: string
  modifiedAt: Date
}

/**
 * Turns one dumped file into a mail, or null to skip it. The sink can only match on recipient if
 * the parser can recover one, so a dump format that writes bodies without headers is not enough.
 */
export type DumpParser = (file: DumpedFile) => RawMail | null

export interface LogDirSinkOptions {
  /** Directory the app's logging mail transport writes into (e.g. a bind mount). */
  dir: string
  /** Default: `parseEml`, which understands RFC 822 header blocks. */
  parse?: DumpParser
}

/**
 * Minimal RFC 822 parser: a header block, a blank line, then the body. Multipart bodies are not
 * decoded; a single text or HTML part is enough for assertions on links and codes.
 */
export const parseEml: DumpParser = (file) => {
  const normalised = file.content.replace(/\r\n/g, '\n')
  const split = normalised.indexOf('\n\n')
  if (split < 0) return null
  const headerBlock = normalised.slice(0, split).replace(/\n[ \t]+/g, ' ')
  const body = normalised.slice(split + 2)
  const headers = new Map<string, string>()
  for (const line of headerBlock.split('\n')) {
    const colon = line.indexOf(':')
    if (colon > 0)
      headers.set(line.slice(0, colon).trim().toLowerCase(), line.slice(colon + 1).trim())
  }
  const toHeader = headers.get('to')
  if (!toHeader) return null
  const to = [...toHeader.matchAll(/[^\s<>,"]+@[^\s<>,"]+/g)].map((match) => match[0])
  const isHtml = /text\/html/i.test(headers.get('content-type') ?? '') || /<html[\s>]/i.test(body)
  const dateHeader = headers.get('date')
  const parsedDate = dateHeader ? new Date(dateHeader) : undefined
  return {
    id: file.name,
    to,
    from: headers.get('from'),
    subject: headers.get('subject') ?? '',
    text: isHtml ? '' : body,
    html: isHtml ? body : '',
    receivedAt: parsedDate && !Number.isNaN(parsedDate.getTime()) ? parsedDate : file.modifiedAt,
  }
}

/** MailSink over a directory of dumped messages — zero infrastructure when the app can write one. */
export class LogDirSink implements MailSink {
  private readonly parse: DumpParser

  constructor(private readonly options: LogDirSinkOptions) {
    this.parse = options.parse ?? parseEml
  }

  async waitForMessage(query: MailQuery): Promise<MailMessage> {
    const found = await pollUntil(
      async () => newest((await this.readAll()).filter((raw) => matchesQuery(raw, query))),
      {
        timeoutMs: query.timeoutMs ?? 10_000,
        intervalMs: query.pollIntervalMs ?? 250,
        describe: `mail to ${query.to} in ${this.options.dir}`,
      },
    )
    return toMailMessage(found)
  }

  async purge(to?: string): Promise<void> {
    for (const file of await this.readFiles()) {
      const raw = to ? this.parse(file) : null
      if (!to || (raw && raw.to.some((address) => address.toLowerCase() === to.toLowerCase()))) {
        await rm(join(this.options.dir, file.name), { force: true })
      }
    }
  }

  private async readAll(): Promise<RawMail[]> {
    return (await this.readFiles())
      .map((file) => this.parse(file))
      .filter((raw): raw is RawMail => raw !== null)
  }

  private async readFiles(): Promise<DumpedFile[]> {
    let names: string[]
    try {
      names = await readdir(this.options.dir)
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') return []
      throw error
    }
    const files: DumpedFile[] = []
    for (const name of names) {
      const path = join(this.options.dir, name)
      try {
        const info = await stat(path)
        if (!info.isFile()) continue
        files.push({ name, content: await readFile(path, 'utf8'), modifiedAt: info.mtime })
      } catch (error) {
        // A file removed between readdir and read is a normal race with a concurrent purge.
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
      }
    }
    return files
  }
}
