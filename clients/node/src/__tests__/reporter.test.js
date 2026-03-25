'use strict'

const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')

/**
 * Reporter tests — in-process buffer.
 *
 * The reporter no longer POSTs to a sidecar; it buffers metrics in memory.
 * These tests verify the buffer API that the native cost projector will consume.
 */

const reporter = require('../reporter')

beforeEach(() => reporter.clear())

// ── buffering ─────────────────────────────────────────────────────────────────

test('buffers metrics when recording is active', () => {
  reporter.startRecording()
  reporter.report({ route: 'GET /api/users/{id}', method: 'GET', status: 200, durationMs: 42, egressBytes: 1024 })
  const metrics = reporter.getMetrics()
  assert.equal(metrics.length, 1)
  assert.equal(metrics[0].route, 'GET /api/users/{id}')
  assert.equal(metrics[0].method, 'GET')
  assert.equal(metrics[0].status, 200)
  assert.equal(metrics[0].durationMs, 42)
  assert.equal(metrics[0].egressBytes, 1024)
})

test('drops metrics when recording is not active', () => {
  // recording is stopped by default (clear() resets it)
  reporter.report({ route: 'GET /api/test', method: 'GET', status: 200, durationMs: 5 })
  assert.equal(reporter.getMetrics().length, 0)
})

test('method is uppercased', () => {
  reporter.startRecording()
  reporter.report({ route: 'POST /api/orders', method: 'post', status: 201, durationMs: 10 })
  assert.equal(reporter.getMetrics()[0].method, 'POST')
})

test('egressBytes defaults to 0 when omitted', () => {
  reporter.startRecording()
  reporter.report({ route: 'GET /ping', method: 'GET', status: 200, durationMs: 5 })
  assert.equal(reporter.getMetrics()[0].egressBytes, 0)
})

test('each metric has a ts timestamp', () => {
  reporter.startRecording()
  const before = Date.now()
  reporter.report({ route: 'GET /api/test', method: 'GET', status: 200, durationMs: 1 })
  const after = Date.now()
  const ts = reporter.getMetrics()[0].ts
  assert.ok(ts >= before && ts <= after, `ts ${ts} outside [${before}, ${after}]`)
})

// ── recording lifecycle ───────────────────────────────────────────────────────

test('startRecording clears previous metrics', () => {
  reporter.startRecording()
  reporter.report({ route: 'GET /old', method: 'GET', status: 200, durationMs: 1 })
  reporter.startRecording()   // clears buffer
  reporter.report({ route: 'GET /new', method: 'GET', status: 200, durationMs: 1 })
  const metrics = reporter.getMetrics()
  assert.equal(metrics.length, 1)
  assert.equal(metrics[0].route, 'GET /new')
})

test('stopRecording stops buffering', () => {
  reporter.startRecording()
  reporter.report({ route: 'GET /before', method: 'GET', status: 200, durationMs: 1 })
  reporter.stopRecording()
  reporter.report({ route: 'GET /after', method: 'GET', status: 200, durationMs: 1 })
  assert.equal(reporter.getMetrics().length, 1)
  assert.equal(reporter.getMetrics()[0].route, 'GET /before')
})

test('getMetrics returns a snapshot, not the live buffer', () => {
  reporter.startRecording()
  reporter.report({ route: 'GET /api/a', method: 'GET', status: 200, durationMs: 1 })
  const snap = reporter.getMetrics()
  reporter.report({ route: 'GET /api/b', method: 'GET', status: 200, durationMs: 1 })
  assert.equal(snap.length, 1)           // snapshot unchanged
  assert.equal(reporter.getMetrics().length, 2)  // live buffer has both
})

// ── resilience ────────────────────────────────────────────────────────────────

test('report() never throws', () => {
  reporter.startRecording()
  // passing garbage should not throw
  assert.doesNotThrow(() => reporter.report({}))
  assert.doesNotThrow(() => reporter.report(null))
})

test('report() returns immediately (synchronous)', () => {
  reporter.startRecording()
  const start = Date.now()
  for (let i = 0; i < 1000; i++) {
    reporter.report({ route: 'GET /api/test', method: 'GET', status: 200, durationMs: 1 })
  }
  assert.ok(Date.now() - start < 100, '1000 report() calls should complete in <100ms')
})
