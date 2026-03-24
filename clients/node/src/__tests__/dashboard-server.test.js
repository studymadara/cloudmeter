'use strict'

const { test } = require('node:test')
const assert   = require('node:assert/strict')
const http     = require('http')

const reporter = require('../reporter')

// ── helpers ───────────────────────────────────────────────────────────────────

function request(port, method, path) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { hostname: '127.0.0.1', port, method, path },
      (res) => {
        let body = ''
        res.on('data', c => { body += c })
        res.on('end', () => resolve({ status: res.statusCode, body, headers: res.headers }))
      }
    )
    req.on('error', reject)
    req.end()
  })
}

// ── tests ─────────────────────────────────────────────────────────────────────

test('GET /api/projections returns 200 JSON', async () => {
  // Fresh require to get a clean server state
  delete require.cache[require.resolve('../dashboard-server')]
  const { start } = require('../dashboard-server')

  reporter.clear()
  reporter.startRecording()

  const port = 17780
  start({ port })
  await new Promise(r => setTimeout(r, 50))

  const res = await request(port, 'GET', '/api/projections')
  assert.equal(res.status, 200)
  assert.ok(res.headers['content-type'].includes('application/json'))
  const data = JSON.parse(res.body)
  assert.ok(data.meta)
  assert.ok(Array.isArray(data.projections))
  assert.ok(data.summary)
})

test('GET /api/stats returns 200 JSON with stats array', async () => {
  delete require.cache[require.resolve('../dashboard-server')]
  const { start } = require('../dashboard-server')

  reporter.clear()
  reporter.startRecording()

  const port = 17781
  start({ port })
  await new Promise(r => setTimeout(r, 50))

  const res = await request(port, 'GET', '/api/stats')
  assert.equal(res.status, 200)
  const data = JSON.parse(res.body)
  assert.ok(Array.isArray(data.stats))
})

test('POST /api/recording/start returns status=recording', async () => {
  delete require.cache[require.resolve('../dashboard-server')]
  const { start } = require('../dashboard-server')

  const port = 17782
  start({ port })
  await new Promise(r => setTimeout(r, 50))

  const res = await request(port, 'POST', '/api/recording/start')
  assert.equal(res.status, 200)
  const data = JSON.parse(res.body)
  assert.equal(data.status, 'recording')
})

test('POST /api/recording/stop returns status=stopped', async () => {
  delete require.cache[require.resolve('../dashboard-server')]
  const { start } = require('../dashboard-server')

  const port = 17783
  start({ port })
  await new Promise(r => setTimeout(r, 50))

  const res = await request(port, 'POST', '/api/recording/stop')
  assert.equal(res.status, 200)
  const data = JSON.parse(res.body)
  assert.equal(data.status, 'stopped')
})

test('unknown route returns 404', async () => {
  delete require.cache[require.resolve('../dashboard-server')]
  const { start } = require('../dashboard-server')

  const port = 17784
  start({ port })
  await new Promise(r => setTimeout(r, 50))

  const res = await request(port, 'GET', '/no-such-endpoint')
  assert.equal(res.status, 404)
})

test('second start() call is a no-op (no port conflict)', async () => {
  delete require.cache[require.resolve('../dashboard-server')]
  const { start } = require('../dashboard-server')

  const port = 17785
  start({ port })
  await new Promise(r => setTimeout(r, 50))

  // Calling start again must not throw
  assert.doesNotThrow(() => start({ port }))
})

test('EADDRINUSE is silently swallowed', async () => {
  delete require.cache[require.resolve('../dashboard-server')]
  const { start } = require('../dashboard-server')

  const port = 17786
  // Start a plain server on the same port first
  const blocker = http.createServer((_, res) => { res.writeHead(200); res.end() })
  await new Promise(r => blocker.listen(port, '127.0.0.1', r))

  // dashboard-server should silently no-op
  assert.doesNotThrow(() => start({ port }))
  await new Promise(r => setTimeout(r, 80))

  blocker.close()
})

test('meta block contains provider and targetUsers', async () => {
  delete require.cache[require.resolve('../dashboard-server')]
  const { start } = require('../dashboard-server')

  reporter.clear()
  reporter.startRecording()

  const port = 17787
  start({ provider: 'GCP', targetUsers: 500, port })
  await new Promise(r => setTimeout(r, 50))

  const res = await request(port, 'GET', '/api/projections')
  const data = JSON.parse(res.body)
  assert.equal(data.meta.provider, 'GCP')
  assert.equal(data.meta.targetUsers, 500)
})
