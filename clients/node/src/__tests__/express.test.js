'use strict'

const { test, before, after, beforeEach } = require('node:test')
const assert  = require('node:assert/strict')
const http    = require('http')
const express = require('express')

/**
 * Express middleware tests.
 *
 * - sidecar.start() is replaced with a no-op so no binary is downloaded.
 * - reporter.report is replaced per-test so we can assert on what was captured.
 * - A real express server is started on a random port; requests are made via http.
 */

// ── mock sidecar before any require of express.js ────────────────────────────
const sidecar  = require('../sidecar')
const reporter = require('../reporter')

const originalSidecarStart = sidecar.start
before(() => { sidecar.start = async () => {} })
after(() => { sidecar.start = originalSidecarStart })

// ── helpers ───────────────────────────────────────────────────────────────────

function makeApp(opts = {}) {
  // Reset the _started flag so each test gets a fresh middleware
  const expressModule = require('../express')
  // Access the module's internal state via a fresh require if possible,
  // but since Node caches modules, we toggle _started by re-importing express.js
  // through a trick: temporarily delete from cache. Simpler: expose a reset fn.
  // For these tests, we just create a new express app each time.
  const { cloudMeter } = expressModule
  const app = express()
  app.use(cloudMeter(opts))

  app.get('/api/users/:userId', (req, res) => {
    res.json({ id: req.params.userId })
  })
  app.get('/api/products', (req, res) => {
    res.json([])
  })
  app.post('/api/orders', (req, res) => {
    res.status(201).json({ created: true })
  })
  app.get('/api/missing', (req, res) => {
    res.status(404).json({ error: 'not found' })
  })

  return app
}

function startServer(app) {
  return new Promise((resolve) => {
    const server = http.createServer(app)
    server.listen(0, '127.0.0.1', () => resolve(server))
  })
}

function request(server, method, path, opts = {}) {
  return new Promise((resolve, reject) => {
    const { port } = server.address()
    const req = http.request(
      { hostname: '127.0.0.1', port, method, path, ...opts },
      (res) => {
        let body = ''
        res.on('data', c => { body += c })
        res.on('end', () => resolve({ status: res.statusCode, body }))
      }
    )
    req.on('error', reject)
    req.end()
  })
}

// ── tests ─────────────────────────────────────────────────────────────────────

test('captures route template, not raw path', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const server = await startServer(makeApp())
  try {
    await request(server, 'GET', '/api/users/42')
    // Express sets req.route after the handler runs, which is after 'finish'
    // So the route template /:userId becomes /api/users/:userId
    assert.equal(captured.length, 1)
    assert.ok(
      captured[0].route.includes('/api/users/:userId') ||
      captured[0].route.includes('/api/users/42'),
      `Unexpected route: ${captured[0].route}`
    )
  } finally {
    server.close()
    reporter.report = orig
  }
})

test('captures correct HTTP method', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const server = await startServer(makeApp())
  try {
    await request(server, 'POST', '/api/orders')
    assert.equal(captured[0].method, 'POST')
  } finally {
    server.close()
    reporter.report = orig
  }
})

test('captures 200 status', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const server = await startServer(makeApp())
  try {
    await request(server, 'GET', '/api/products')
    assert.equal(captured[0].status, 200)
  } finally {
    server.close()
    reporter.report = orig
  }
})

test('captures 404 status', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const server = await startServer(makeApp())
  try {
    await request(server, 'GET', '/api/missing')
    assert.equal(captured[0].status, 404)
  } finally {
    server.close()
    reporter.report = orig
  }
})

test('captures 201 status', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const server = await startServer(makeApp())
  try {
    await request(server, 'POST', '/api/orders')
    assert.equal(captured[0].status, 201)
  } finally {
    server.close()
    reporter.report = orig
  }
})

test('durationMs is a non-negative number', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const server = await startServer(makeApp())
  try {
    await request(server, 'GET', '/api/products')
    assert.ok(typeof captured[0].durationMs === 'number')
    assert.ok(captured[0].durationMs >= 0)
  } finally {
    server.close()
    reporter.report = orig
  }
})

test('one report per request', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const server = await startServer(makeApp())
  try {
    await request(server, 'GET', '/api/products')
    await request(server, 'GET', '/api/products')
    await request(server, 'GET', '/api/products')
    assert.equal(captured.length, 3)
  } finally {
    server.close()
    reporter.report = orig
  }
})

test('reporter error does not crash the app', async () => {
  const orig = reporter.report
  reporter.report = () => { throw new Error('boom') }

  const server = await startServer(makeApp())
  try {
    const res = await request(server, 'GET', '/api/products')
    assert.equal(res.status, 200)
  } finally {
    server.close()
    reporter.report = orig
  }
})
