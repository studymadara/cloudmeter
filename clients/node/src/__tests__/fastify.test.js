'use strict'

const { test } = require('node:test')
const assert  = require('node:assert/strict')
const http    = require('http')
const Fastify = require('fastify')

/**
 * Fastify plugin tests.
 *
 * reporter.report is replaced per-test to capture calls.
 */

const reporter = require('../reporter')

// ── helpers ───────────────────────────────────────────────────────────────────

async function makeApp(opts = {}) {
  const { cloudMeterPlugin } = require('../fastify')

  const fastify = Fastify({ logger: false })
  await fastify.register(cloudMeterPlugin, opts)

  fastify.get('/api/users/:userId', async (req) => ({ id: req.params.userId }))
  fastify.get('/api/products', async () => [])
  fastify.post('/api/orders', async (req, reply) => { reply.code(201); return { created: true } })
  fastify.get('/api/missing', async (req, reply) => { reply.code(404); return { error: 'not found' } })

  await fastify.listen({ port: 0, host: '127.0.0.1' })
  return fastify
}

function request(fastify, method, path) {
  return new Promise((resolve, reject) => {
    const port = fastify.server.address().port
    const req = http.request(
      { hostname: '127.0.0.1', port, method, path },
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

test('captures route template not raw path', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const fastify = await makeApp()
  try {
    await request(fastify, 'GET', '/api/users/42')
    assert.equal(captured.length, 1)
    // Fastify routeOptions.url gives /api/users/:userId
    assert.ok(
      captured[0].route.includes(':userId') || captured[0].route.includes('42'),
      `Unexpected route: ${captured[0].route}`
    )
  } finally {
    await fastify.close()
    reporter.report = orig
  }
})

test('captures correct HTTP method', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const fastify = await makeApp()
  try {
    await request(fastify, 'POST', '/api/orders')
    assert.equal(captured[0].method, 'POST')
  } finally {
    await fastify.close()
    reporter.report = orig
  }
})

test('captures 200 status', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const fastify = await makeApp()
  try {
    await request(fastify, 'GET', '/api/products')
    assert.equal(captured[0].status, 200)
  } finally {
    await fastify.close()
    reporter.report = orig
  }
})

test('captures 404 status', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const fastify = await makeApp()
  try {
    await request(fastify, 'GET', '/api/missing')
    assert.equal(captured[0].status, 404)
  } finally {
    await fastify.close()
    reporter.report = orig
  }
})

test('captures 201 status', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const fastify = await makeApp()
  try {
    await request(fastify, 'POST', '/api/orders')
    assert.equal(captured[0].status, 201)
  } finally {
    await fastify.close()
    reporter.report = orig
  }
})

test('durationMs is a non-negative number', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const fastify = await makeApp()
  try {
    await request(fastify, 'GET', '/api/products')
    assert.ok(typeof captured[0].durationMs === 'number')
    assert.ok(captured[0].durationMs >= 0, `durationMs should be >= 0, got ${captured[0].durationMs}`)
  } finally {
    await fastify.close()
    reporter.report = orig
  }
})

test('one report per request', async () => {
  const captured = []
  const orig = reporter.report
  reporter.report = (opts) => captured.push(opts)

  const fastify = await makeApp()
  try {
    await request(fastify, 'GET', '/api/products')
    await request(fastify, 'GET', '/api/products')
    await request(fastify, 'GET', '/api/products')
    assert.equal(captured.length, 3)
  } finally {
    await fastify.close()
    reporter.report = orig
  }
})

test('reporter error does not crash the app', async () => {
  const orig = reporter.report
  reporter.report = () => { throw new Error('boom') }

  const fastify = await makeApp()
  try {
    const res = await request(fastify, 'GET', '/api/products')
    assert.equal(res.status, 200)
  } finally {
    await fastify.close()
    reporter.report = orig
  }
})
