'use strict'

const { test } = require('node:test')
const assert   = require('node:assert/strict')
const { project, computeStats, SCALE_USERS } = require('../cost-projector')

// ── helpers ───────────────────────────────────────────────────────────────────

function makeMetrics(count, overrides = {}) {
  const base = Date.now() - 1_000
  return Array.from({ length: count }, (_, i) => ({
    route:       'GET /api/test',
    method:      'GET',
    status:      200,
    durationMs:  50,
    egressBytes: 1024,
    ts:          base + i * 10,
    warmup:      false,  // reporter.js sets this — live metrics only in these tests
    ...overrides,
  }))
}

// ── project() ─────────────────────────────────────────────────────────────────

test('empty metrics returns empty projections', () => {
  const { projections } = project([])
  assert.equal(projections.length, 0)
})

test('all-warmup metrics returns empty projections', () => {
  const metrics = makeMetrics(10, { warmup: true })
  const { projections, warmupCount } = project(metrics)
  assert.equal(projections.length, 0)
  assert.equal(warmupCount, 10)
})

test('single route produces one projection', () => {
  const { projections } = project(makeMetrics(10))
  assert.equal(projections.length, 1)
  assert.ok(projections[0].route.includes('/api/test'))
})

test('projection has required fields', () => {
  const { projections } = project(makeMetrics(10))
  const p = projections[0]
  assert.ok(typeof p.observedRps              === 'number')
  assert.ok(typeof p.projectedRps             === 'number')
  assert.ok(typeof p.projectedMonthlyCostUsd  === 'number')
  assert.ok(typeof p.projectedCostPerUserUsd  === 'number')
  assert.ok(typeof p.recommendedInstance      === 'string')
  assert.ok(typeof p.medianDurationMs         === 'number')
  assert.ok(typeof p.medianCpuMs              === 'number')
  assert.ok(typeof p.exceedsBudget            === 'boolean')
  assert.ok(Array.isArray(p.costCurve))
})

test('cost curve has 12 scale points', () => {
  const { projections } = project(makeMetrics(20))
  assert.equal(projections[0].costCurve.length, 12)
  assert.equal(projections[0].costCurve.length, SCALE_USERS.length)
})

test('cost curve scale points match SCALE_USERS', () => {
  const { projections } = project(makeMetrics(20))
  const users = projections[0].costCurve.map(p => p.users)
  assert.deepEqual(users, SCALE_USERS)
})

test('monthly cost is non-negative', () => {
  const { projections } = project(makeMetrics(20))
  for (const p of projections) {
    assert.ok(p.projectedMonthlyCostUsd >= 0, `cost should be >= 0, got ${p.projectedMonthlyCostUsd}`)
  }
})

test('projections sorted descending by cost', () => {
  const base = Date.now() - 1_000
  const metrics = [
    ...Array.from({ length: 10 }, (_, i) => ({ route: 'GET /cheap', method: 'GET', status: 200, durationMs: 5,   egressBytes: 100, ts: base + i * 10, warmup: false })),
    ...Array.from({ length: 10 }, (_, i) => ({ route: 'GET /heavy', method: 'GET', status: 200, durationMs: 500, egressBytes: 1_000_000, ts: base + i * 10 + 1, warmup: false })),
  ]
  const { projections } = project(metrics)
  assert.equal(projections.length, 2)
  assert.ok(
    projections[0].projectedMonthlyCostUsd >= projections[1].projectedMonthlyCostUsd,
    'projections should be sorted descending'
  )
})

test('exceedsBudget is true when cost > budgetUsd', () => {
  const metrics = makeMetrics(100, { durationMs: 500, egressBytes: 1_000_000 })
  const { projections } = project(metrics, { targetUsers: 1_000_000, requestsPerUserPerSecond: 10, budgetUsd: 1 })
  assert.ok(projections[0].exceedsBudget, 'expected exceedsBudget=true for very high load')
})

test('exceedsBudget is false when budgetUsd is 0', () => {
  const { projections } = project(makeMetrics(10), { budgetUsd: 0 })
  assert.equal(projections[0].exceedsBudget, false)
})

test('GCP provider uses GCP instances', () => {
  const { projections } = project(makeMetrics(10), { provider: 'GCP' })
  const inst = projections[0].recommendedInstance
  // GCP instances start with e2, n2, or c2
  assert.ok(/^(e2|n2|c2)/.test(inst), `Expected GCP instance, got ${inst}`)
})

test('AZURE provider uses Azure instances', () => {
  const { projections } = project(makeMetrics(10), { provider: 'AZURE' })
  const inst = projections[0].recommendedInstance
  // Azure instances start with B, D, F
  assert.ok(/^[BDF]/.test(inst), `Expected Azure instance, got ${inst}`)
})

test('warmupCount counts metrics with warmup=true', () => {
  const live = makeMetrics(15)                        // warmup: false
  const warm = makeMetrics(5, { warmup: true })       // warmup: true
  const { warmupCount } = project([...live, ...warm])
  assert.equal(warmupCount, 5)
})

// ── computeStats() ────────────────────────────────────────────────────────────

test('computeStats returns one entry per route', () => {
  const metrics = makeMetrics(20)
  const { projections } = project(metrics)
  const stats = computeStats(metrics, projections)
  assert.equal(stats.length, 1)
})

test('computeStats fields are present', () => {
  const metrics = makeMetrics(20)
  const { projections } = project(metrics)
  const [s] = computeStats(metrics, projections)
  assert.ok(typeof s.route           === 'string')
  assert.ok(typeof s.requests        === 'number')
  assert.ok(typeof s.p50CostUsd      === 'number')
  assert.ok(typeof s.p95CostUsd      === 'number')
  assert.ok(typeof s.p99CostUsd      === 'number')
  assert.ok(typeof s.varianceRatio   === 'number')
  assert.ok(typeof s.varianceWarning === 'boolean')
})

test('p50 <= p95 <= p99', () => {
  const metrics = makeMetrics(50)
  const { projections } = project(metrics)
  const [s] = computeStats(metrics, projections)
  assert.ok(s.p50CostUsd <= s.p95CostUsd, 'p50 should be <= p95')
  assert.ok(s.p95CostUsd <= s.p99CostUsd, 'p95 should be <= p99')
})

test('varianceWarning true when p95/p50 > 1.5', () => {
  const base = Date.now() - 1_000
  // Mix of cheap and expensive requests → high variance
  const metrics = [
    ...Array.from({ length: 40 }, (_, i) => ({ route: 'GET /v', method: 'GET', status: 200, durationMs: 5,   egressBytes: 0, ts: base + i * 10, warmup: false })),
    ...Array.from({ length: 10 }, (_, i) => ({ route: 'GET /v', method: 'GET', status: 200, durationMs: 500, egressBytes: 0, ts: base + 500 + i * 10, warmup: false })),
  ]
  const { projections } = project(metrics)
  const [s] = computeStats(metrics, projections)
  // p95 should be significantly higher than p50
  assert.ok(s.varianceRatio >= 1.0, `expected high variance ratio, got ${s.varianceRatio}`)
})

test('computeStats empty metrics returns empty array', () => {
  const stats = computeStats([], [], {})
  assert.equal(stats.length, 0)
})
