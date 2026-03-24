'use strict'

// Native Node.js dashboard server — no Express dependency.
// Starts on 127.0.0.1:7777 by default.
// Silently no-ops if the port is already in use.

const http     = require('http')
const fs       = require('fs')
const path     = require('path')
const reporter = require('./reporter')
const { project, computeStats, PRICING_DATE } = require('./cost-projector')

const DASHBOARD_HTML_PATH = path.join(__dirname, 'dashboard.html')

let _server = null

// start(opts) — call once from express.js / fastify.js on first middleware init.
// Calling start() a second time is a no-op.
function start(opts = {}) {
  if (_server) return

  const {
    provider                 = 'AWS',
    region                   = 'us-east-1',
    targetUsers              = 1000,
    requestsPerUserPerSecond = 1.0,
    budgetUsd                = 0,
    port                     = 7777,
  } = opts

  const projOpts = { provider, region, targetUsers, requestsPerUserPerSecond, budgetUsd }

  _server = http.createServer((req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*')

    // ── GET / → serve dashboard UI ───────────────────────────────────────────
    if (req.method === 'GET' && req.url === '/') {
      fs.readFile(DASHBOARD_HTML_PATH, (err, html) => {
        if (err) {
          res.writeHead(500, { 'Content-Type': 'text/plain' })
          res.end('dashboard.html not found')
          return
        }
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
        res.end(html)
      })
      return
    }

    // ── GET /api/projections → cost projections ───────────────────────────────
    if (req.method === 'GET' && req.url === '/api/projections') {
      const metrics = reporter.getMetrics()
      const { projections, warmupCount } = project(metrics, projOpts)
      const total       = projections.reduce((s, p) => s + p.projectedMonthlyCostUsd, 0)
      const totalRounded = Math.round(total * 100) / 100

      const body = JSON.stringify({
        meta: {
          provider,
          region,
          targetUsers,
          requestsPerUserPerSecond,
          budgetUsd,
          pricingDate:   PRICING_DATE,
          pricingSource: 'static',
        },
        projections,
        summary: {
          totalProjectedMonthlyCostUsd: totalRounded,
          anyExceedsBudget:             projections.some(p => p.exceedsBudget),
          warmupMetricsExcluded:        warmupCount,
        },
      })
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(body)
      return
    }

    // ── GET /api/stats → p50/p95/p99 variance ────────────────────────────────
    if (req.method === 'GET' && req.url === '/api/stats') {
      const metrics = reporter.getMetrics()
      const { projections } = project(metrics, projOpts)
      const stats = computeStats(metrics, projections, projOpts)
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ stats }))
      return
    }

    // ── POST /api/recording/start ─────────────────────────────────────────────
    if (req.method === 'POST' && req.url === '/api/recording/start') {
      reporter.startRecording()
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ status: 'recording' }))
      return
    }

    // ── POST /api/recording/stop ──────────────────────────────────────────────
    if (req.method === 'POST' && req.url === '/api/recording/stop') {
      reporter.stopRecording()
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify({ status: 'stopped' }))
      return
    }

    res.writeHead(404)
    res.end()
  })

  _server.listen(port, '127.0.0.1', () => {
    // Use process.stdout directly so the message works in any framework
    process.stdout.write(`[cloudmeter] dashboard → http://127.0.0.1:${port}\n`)
  })

  _server.on('error', err => {
    if (err.code === 'EADDRINUSE') {
      // Another process (or a second app instance) is already on this port.
      // Silently back off — the existing server is serving the dashboard.
      _server = null
    }
  })
}

// _stop() is for tests only — closes the server and resets state so
// a new start() call on a different port works within the same process.
function _stop() {
  if (_server) { _server.close(); _server = null }
}

module.exports = { start, _stop }
