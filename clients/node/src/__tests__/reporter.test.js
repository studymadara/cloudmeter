'use strict'

const { test, before, after, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
const http   = require('http')

/**
 * Reporter tests.
 * Spins up a real local HTTP server to receive the POSTed metric —
 * no mocking of built-in http, so this proves the real wire format.
 */

let server
let serverPort
let received = []

before(async () => {
  await new Promise((resolve) => {
    server = http.createServer((req, res) => {
      let body = ''
      req.on('data', chunk => { body += chunk })
      req.on('end', () => {
        try { received.push({ method: req.method, path: req.url, body: JSON.parse(body) }) }
        catch (_) { received.push({ method: req.method, path: req.url, body }) }
        res.writeHead(202)
        res.end()
      })
    })
    server.listen(0, '127.0.0.1', () => {
      serverPort = server.address().port
      resolve()
    })
  })
})

after(() => server.close())

beforeEach(() => { received = [] })

function waitForReport(ms = 200) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

// ── tests ─────────────────────────────────────────────────────────────────────

test('posts to /api/metrics with correct JSON shape', async () => {
  const reporter = require('../reporter')
  reporter.setPort(serverPort)

  reporter.report({
    route:       'GET /api/users/{id}',
    method:      'get',
    status:      200,
    durationMs:  42,
    egressBytes: 1024,
  })

  await waitForReport()

  assert.equal(received.length, 1)
  assert.equal(received[0].method, 'POST')
  assert.equal(received[0].path, '/api/metrics')
  assert.equal(received[0].body.route, 'GET /api/users/{id}')
  assert.equal(received[0].body.method, 'GET')   // uppercased
  assert.equal(received[0].body.status, 200)
  assert.equal(received[0].body.durationMs, 42)
  assert.equal(received[0].body.egressBytes, 1024)
})

test('egressBytes defaults to 0 when omitted', async () => {
  const reporter = require('../reporter')
  reporter.setPort(serverPort)

  reporter.report({ route: 'GET /ping', method: 'GET', status: 200, durationMs: 5 })
  await waitForReport()

  assert.equal(received[0].body.egressBytes, 0)
})

test('method is uppercased', async () => {
  const reporter = require('../reporter')
  reporter.setPort(serverPort)

  reporter.report({ route: 'POST /api/orders', method: 'post', status: 201, durationMs: 10 })
  await waitForReport()

  assert.equal(received[0].body.method, 'POST')
})

test('does not throw when sidecar is unreachable', async () => {
  const reporter = require('../reporter')
  reporter.setPort(19999) // nothing listening here

  // should not throw
  reporter.report({ route: 'GET /api/test', method: 'GET', status: 200, durationMs: 1 })
  await waitForReport()
  // reaching here = pass
})

test('report() returns immediately (fire-and-forget)', () => {
  const reporter = require('../reporter')
  reporter.setPort(serverPort)

  const start = Date.now()
  reporter.report({ route: 'GET /api/test', method: 'GET', status: 200, durationMs: 1 })
  const elapsed = Date.now() - start

  // Should return well under 20ms — the actual HTTP POST happens asynchronously
  assert.ok(elapsed < 50, `report() took ${elapsed}ms — should be near-instant`)
})
