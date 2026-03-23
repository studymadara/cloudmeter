'use strict'

/**
 * Fire-and-forget metric reporter.
 * Uses Node's built-in http — zero dependencies.
 * All errors are silently swallowed. This must never crash the host app.
 */

const http = require('http')

let _port = 7778

function setPort(port) {
  _port = port
}

function report({ route, method, status, durationMs, egressBytes = 0 }) {
  try {
    const body = JSON.stringify({
      route,
      method: method.toUpperCase(),
      status,
      durationMs,
      egressBytes,
    })
    const req = http.request({
      hostname: '127.0.0.1',
      port: _port,
      path: '/api/metrics',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
      },
    })
    req.on('error', () => {}) // fire-and-forget — never throw
    req.end(body)
  } catch (_) {
    // swallow everything — reporter must never crash the host app
  }
}

module.exports = { report, setPort }
