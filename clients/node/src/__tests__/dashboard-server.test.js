'use strict'

const { test, afterEach } = require('node:test')
const assert   = require('node:assert/strict')
const http     = require('http')

const reporter = require('../reporter')
// Single require — _stop() resets internal state between tests so c8 tracks coverage correctly
const { start, _stop } = require('../dashboard-server')

// Each test gets a fresh server on its own port and a clean reporter buffer
let _port = 17800

function nextPort() { return _port++ }

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

async function startServer(opts = {}) {
  const port = nextPort()
  reporter.clear()
  reporter.startRecording()
  start({ port, ...opts })
  await new Promise(r => setTimeout(r, 40))
  return port
}

afterEach(() => {
  _stop()
  reporter.clear()
})

// ── GET / ─────────────────────────────────────────────────────────────────────

test('GET / serves dashboard HTML', async () => {
  const port = await startServer()
  const res  = await request(port, 'GET', '/')
  assert.equal(res.status, 200)
  assert.ok(res.headers['content-type'].includes('text/html'))
  assert.ok(res.body.includes('<title>CloudMeter'))
})

// ── GET /api/projections ──────────────────────────────────────────────────────

test('GET /api/projections returns 200 JSON', async () => {
  const port = await startServer()
  const res  = await request(port, 'GET', '/api/projections')
  assert.equal(res.status, 200)
  assert.ok(res.headers['content-type'].includes('application/json'))
  const data = JSON.parse(res.body)
  assert.ok(data.meta)
  assert.ok(Array.isArray(data.projections))
  assert.ok(data.summary)
})

test('meta block contains provider and targetUsers', async () => {
  const port = await startServer({ provider: 'GCP', targetUsers: 500 })
  const res  = await request(port, 'GET', '/api/projections')
  const data = JSON.parse(res.body)
  assert.equal(data.meta.provider, 'GCP')
  assert.equal(data.meta.targetUsers, 500)
})

test('meta pricingSource is static', async () => {
  const port = await startServer()
  const res  = await request(port, 'GET', '/api/projections')
  const data = JSON.parse(res.body)
  assert.equal(data.meta.pricingSource, 'static')
})

test('summary has totalProjectedMonthlyCostUsd', async () => {
  const port = await startServer()
  const res  = await request(port, 'GET', '/api/projections')
  const data = JSON.parse(res.body)
  assert.ok(typeof data.summary.totalProjectedMonthlyCostUsd === 'number')
})

// ── GET /api/stats ────────────────────────────────────────────────────────────

test('GET /api/stats returns 200 JSON with stats array', async () => {
  const port = await startServer()
  const res  = await request(port, 'GET', '/api/stats')
  assert.equal(res.status, 200)
  const data = JSON.parse(res.body)
  assert.ok(Array.isArray(data.stats))
})

// ── POST /api/recording/start ─────────────────────────────────────────────────

test('POST /api/recording/start returns status=recording', async () => {
  const port = await startServer()
  const res  = await request(port, 'POST', '/api/recording/start')
  assert.equal(res.status, 200)
  assert.equal(JSON.parse(res.body).status, 'recording')
})

// ── POST /api/recording/stop ──────────────────────────────────────────────────

test('POST /api/recording/stop returns status=stopped', async () => {
  const port = await startServer()
  const res  = await request(port, 'POST', '/api/recording/stop')
  assert.equal(res.status, 200)
  assert.equal(JSON.parse(res.body).status, 'stopped')
})

// ── 404 ───────────────────────────────────────────────────────────────────────

test('unknown route returns 404', async () => {
  const port = await startServer()
  const res  = await request(port, 'GET', '/no-such-endpoint')
  assert.equal(res.status, 404)
})

// ── CORS ──────────────────────────────────────────────────────────────────────

test('responses include CORS header', async () => {
  const port = await startServer()
  const res  = await request(port, 'GET', '/api/projections')
  assert.equal(res.headers['access-control-allow-origin'], '*')
})

// ── idempotent start ──────────────────────────────────────────────────────────

test('second start() call is a no-op', async () => {
  const port = await startServer()
  // Calling start() again on the same port must not throw
  assert.doesNotThrow(() => start({ port }))
})

// ── EADDRINUSE ────────────────────────────────────────────────────────────────

test('EADDRINUSE is silently swallowed', async () => {
  const port    = nextPort()
  const blocker = http.createServer((_, res) => { res.writeHead(200); res.end() })
  await new Promise(r => blocker.listen(port, '127.0.0.1', r))

  assert.doesNotThrow(() => start({ port }))
  await new Promise(r => setTimeout(r, 80))

  blocker.close()
})
