import { mkdtemp, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  extractLinks,
  htmlToText,
  LogDirSink,
  MailpitSink,
  parseEml,
  toMailMessage,
} from '../../src/index.js'

describe('toMailMessage', () => {
  const message = toMailMessage({
    id: '1',
    to: ['Parent@Example.Test'],
    subject: 'Verify your email',
    html: '<p>Hi,</p><p>Your code is <b>482913</b>.</p><a href="https://app.test/#/verify?token=a&amp;b=1">Verify</a>',
    receivedAt: new Date('2026-11-03T10:00:00Z'),
  })

  it('derives text from HTML and lower-cases recipients', () => {
    expect(message.text).toContain('Your code is 482913')
    expect(message.to).toEqual(['parent@example.test'])
  })

  it('extracts decoded links and the six-digit code', () => {
    expect(message.links()).toEqual(['https://app.test/#/verify?token=a&b=1'])
    expect(message.code()).toBe('482913')
    expect(message.code(/token=(\w+)/)).toBe('a')
  })

  it('explains a missing code', () => {
    expect(() => message.code(/\b\d{8}\b/)).toThrow(/No code matching/)
  })
})

describe('html helpers', () => {
  it('drops scripts and styles and dedupes links across HTML and text', () => {
    expect(htmlToText('<style>a{}</style><div>One</div><script>x()</script>Two')).toBe('One\nTwo')
    expect(
      extractLinks(
        '<a href="https://a.test/x">x</a>',
        'see https://a.test/x and https://b.test/y.',
      ),
    ).toEqual(['https://a.test/x', 'https://b.test/y.'])
  })
})

describe('MailpitSink', () => {
  const calls: string[] = []
  const fakeFetch = (async (url: string | URL, init?: RequestInit) => {
    const href = String(url)
    calls.push(`${init?.method ?? 'GET'} ${href}`)
    if (href.includes('/api/v1/search') && (init?.method ?? 'GET') === 'GET') {
      return Response.json({
        messages: [
          {
            ID: 'old',
            To: [{ Address: 'a@example.test' }],
            Subject: 'Your code',
            Created: '2026-11-03T09:00:00Z',
          },
          {
            ID: 'new',
            To: [{ Address: 'a@example.test' }],
            Subject: 'Your code',
            Created: '2026-11-03T09:05:00Z',
          },
          {
            ID: 'other',
            To: [{ Address: 'a@example.test' }],
            Subject: 'Welcome',
            Created: '2026-11-03T09:06:00Z',
          },
        ],
      })
    }
    if (href.includes('/api/v1/message/new'))
      return Response.json({ Text: 'Code: 123456', HTML: '' })
    if (init?.method === 'DELETE') return new Response(null, { status: 200 })
    return new Response('nope', { status: 404 })
  }) as typeof fetch

  it('returns the newest matching message with its body', async () => {
    const sink = new MailpitSink({ baseUrl: 'http://mailpit:8025/', fetch: fakeFetch })
    const message = await sink.waitForMessage({ to: 'A@example.test', subjectMatches: /code/i })
    expect(message.id).toBe('new')
    expect(message.code()).toBe('123456')
    expect(calls[0]).toBe(
      'GET http://mailpit:8025/api/v1/search?query=to%3A%22A%40example.test%22&limit=50',
    )
  })

  it('purges by recipient or entirely', async () => {
    const sink = new MailpitSink({ baseUrl: 'http://mailpit:8025', fetch: fakeFetch })
    await sink.purge('a@example.test')
    await sink.purge()
    expect(calls.slice(-2)).toEqual([
      'DELETE http://mailpit:8025/api/v1/search?query=to%3A%22a%40example.test%22',
      'DELETE http://mailpit:8025/api/v1/messages',
    ])
  })

  it('times out with a description of what it waited for', async () => {
    const empty = (async () => Response.json({ messages: [] })) as unknown as typeof fetch
    const sink = new MailpitSink({ baseUrl: 'http://mailpit:8025', fetch: empty })
    await expect(
      sink.waitForMessage({ to: 'x@example.test', timeoutMs: 30, pollIntervalMs: 10 }),
    ).rejects.toThrow(/mail to x@example.test/)
  })
})

describe('LogDirSink', () => {
  let dir: string
  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), 'pitchside-mail-'))
  })
  afterEach(async () => {
    await rm(dir, { recursive: true, force: true })
  })

  const eml = (to: string, subject: string, date: string, body: string): string =>
    `To: Someone <${to}>\nFrom: app@example.test\nSubject: ${subject}\nDate: ${date}\nContent-Type: text/plain\n\n${body}`

  it('parses RFC 822 dumps and finds the newest match', async () => {
    await writeFile(
      join(dir, '1.eml'),
      eml('p@example.test', 'OTP', 'Tue, 03 Nov 2026 09:00:00 GMT', 'code 111111'),
    )
    await writeFile(
      join(dir, '2.eml'),
      eml('p@example.test', 'OTP', 'Tue, 03 Nov 2026 09:01:00 GMT', 'code 222222'),
    )
    await writeFile(join(dir, '3.html'), '<p>body only, no headers</p>')
    const sink = new LogDirSink({ dir })
    const message = await sink.waitForMessage({ to: 'p@example.test', subjectMatches: /OTP/ })
    expect(message.code()).toBe('222222')
  })

  it('purges only one recipient when asked', async () => {
    await writeFile(
      join(dir, 'a.eml'),
      eml('a@example.test', 'Hi', 'Tue, 03 Nov 2026 09:00:00 GMT', 'x'),
    )
    await writeFile(
      join(dir, 'b.eml'),
      eml('b@example.test', 'Hi', 'Tue, 03 Nov 2026 09:00:00 GMT', 'x'),
    )
    await new LogDirSink({ dir }).purge('a@example.test')
    expect(await readdir(dir)).toEqual(['b.eml'])
  })

  it('treats a missing directory as empty and supports a custom parser', async () => {
    const missing = new LogDirSink({ dir: join(dir, 'nope') })
    await expect(
      missing.waitForMessage({ to: 'a@example.test', timeoutMs: 20, pollIntervalMs: 5 }),
    ).rejects.toThrow()

    await writeFile(
      join(dir, 'x.json'),
      JSON.stringify({ to: 'j@example.test', subject: 'S', text: 'code 333333' }),
    )
    const sink = new LogDirSink({
      dir,
      parse: (file) => {
        const data = JSON.parse(file.content) as { to: string; subject: string; text: string }
        return {
          id: file.name,
          to: [data.to],
          subject: data.subject,
          text: data.text,
          receivedAt: file.modifiedAt,
        }
      },
    })
    expect((await sink.waitForMessage({ to: 'j@example.test' })).code()).toBe('333333')
  })

  it('parseEml skips files without a recipient', () => {
    expect(
      parseEml({ name: 'x', content: 'Subject: x\n\nbody', modifiedAt: new Date() }),
    ).toBeNull()
  })
})
