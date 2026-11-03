// A deliberately tiny hash-routed app used only to prove the kernel's fixtures in a real browser.
// No dependencies; it is not a model of any real application.
import { createServer } from 'node:http'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const port = Number(process.env.TOY_APP_PORT ?? 4317)
const mailDir =
  process.env.TOY_APP_MAIL_DIR ?? join(here, '..', '..', '..', 'test-results', 'toy-mail')
mkdirSync(mailDir, { recursive: true })

const html = readFileSync(join(here, 'index.html'), 'utf8')
const users = { member: 'secret', admin: 'secret' }
// Session tokens with rotation and reuse detection, like a real refresh-token scheme: presenting a
// token that was already rotated revokes every session of that user.
const tokens = new Map() // token -> { user, used }
const issue = (user) => {
  const token = `tok-${Math.random().toString(36).slice(2, 12)}`
  tokens.set(token, { user, used: false })
  return token
}
const resolve = (token) => {
  const entry = token && tokens.get(token)
  if (!entry) return undefined
  if (entry.used) {
    for (const value of tokens.values()) if (value.user === entry.user) value.used = true
    return undefined
  }
  return entry.user
}
const items = []
let dataCalls = 0

const cookies = (req) =>
  Object.fromEntries(
    (req.headers.cookie ?? '')
      .split(';')
      .map((part) => part.trim().split('='))
      .filter(([name]) => name),
  )

const body = (req) =>
  new Promise((resolve) => {
    let raw = ''
    req.on('data', (chunk) => (raw += chunk))
    req.on('end', () => resolve(raw ? JSON.parse(raw) : {}))
  })

const json = (res, status, payload, headers = {}) => {
  res.writeHead(status, { 'content-type': 'application/json', ...headers })
  res.end(JSON.stringify(payload))
}

createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${port}`)
  const token = cookies(req).session

  if (req.method === 'POST' && url.pathname === '/api/login') {
    const { username, password } = await body(req)
    if (users[username] && users[username] === password) {
      return json(
        res,
        200,
        { role: username },
        { 'set-cookie': `session=${issue(username)}; HttpOnly; Path=/; SameSite=Lax` },
      )
    }
    return json(res, 401, { error: 'bad credentials' })
  }
  if (req.method === 'POST' && url.pathname === '/api/logout') {
    return json(res, 200, {}, { 'set-cookie': 'session=; Path=/; Max-Age=0' })
  }
  if (url.pathname === '/api/me') {
    const user = resolve(token)
    return user ? json(res, 200, { role: user }) : json(res, 401, { error: 'anonymous' })
  }
  if (req.method === 'POST' && url.pathname === '/api/rotate') {
    const user = resolve(token)
    if (!user) return json(res, 401, { error: 'anonymous' })
    tokens.get(token).used = true
    return json(
      res,
      200,
      {},
      { 'set-cookie': `session=${issue(user)}; HttpOnly; Path=/; SameSite=Lax` },
    )
  }
  if (url.pathname === '/api/data') {
    dataCalls += 1
    return json(res, 200, { value: 'fresh data', calls: dataCalls })
  }
  if (req.method === 'POST' && url.pathname === '/api/items') {
    const item = await body(req)
    items.push(item)
    return json(res, 201, item)
  }
  if (req.method === 'POST' && url.pathname === '/api/signup') {
    const { email } = await body(req)
    const token = Math.random().toString(36).slice(2, 10)
    const code = String(100000 + Math.floor(Math.random() * 900000))
    const eml = [
      `To: <${email}>`,
      'From: toy@example.test',
      'Subject: Confirm your sign-up',
      `Date: ${new Date().toUTCString()}`,
      'Content-Type: text/html',
      '',
      `<p>Your code is <b>${code}</b>.</p><a href="http://localhost:${port}/#/confirm?token=${token}&amp;c=${code}">Confirm</a>`,
    ].join('\n')
    writeFileSync(join(mailDir, `${token}.eml`), eml)
    return json(res, 202, {})
  }
  if (url.pathname === '/' || url.pathname === '/index.html') {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
    return res.end(html)
  }
  res.writeHead(404)
  res.end()
}).listen(port, () => console.log(`toy app on http://localhost:${port}`))
