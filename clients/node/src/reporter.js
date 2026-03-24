'use strict'

/**
 * In-process metric buffer.
 *
 * Replaces the old sidecar HTTP reporter. Metrics are stored in memory
 * and will be consumed by the native cost projector (coming next).
 * All calls are synchronous and never throw — must never crash the host app.
 */

const WARMUP_MS = 30_000  // first 30 s after startRecording() are tagged as warmup

const _buffer = []
let _recording    = false
let _recordingStart = 0

function report(opts) {
  try {
    const { route, method, status, durationMs, egressBytes = 0 } = opts
    if (_recording) {
      const now = Date.now()
      _buffer.push({
        route,
        method:      method.toUpperCase(),
        status,
        durationMs,
        egressBytes,
        ts:          now,
        warmup:      now < _recordingStart + WARMUP_MS,
      })
    }
  } catch (_) {}
}

function startRecording() {
  _buffer.length  = 0
  _recording      = true
  _recordingStart = Date.now()
}

function stopRecording() {
  _recording = false
}

function getMetrics() {
  return _buffer.slice()
}

function clear() {
  _buffer.length = 0
  _recording = false
}

module.exports = { report, startRecording, stopRecording, getMetrics, clear }
