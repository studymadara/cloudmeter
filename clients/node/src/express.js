'use strict'

/**
 * Express middleware.
 *
 * Usage:
 *   const { cloudMeter } = require('cloudmeter')
 *   app.use(cloudMeter({ provider: 'AWS', targetUsers: 1000 }))
 *
 * Options:
 *   provider                  'AWS' | 'GCP' | 'AZURE'  (default: 'AWS')
 *   region                    string                     (default: 'us-east-1')
 *   targetUsers               number                     (default: 1000)
 *   requestsPerUserPerSecond  number                     (default: 1.0)
 *   budgetUsd                 number                     (default: 0 = disabled)
 *   port                      number                     (default: 7777)
 *
 * On first call, starts the dashboard server at http://127.0.0.1:<port>.
 */

const reporter        = require('./reporter')
const dashboardServer = require('./dashboard-server')

let _started = false

function cloudMeter(opts = {}) {
  if (!_started) {
    _started = true
    reporter.startRecording()
    dashboardServer.start(opts)
  }

  return function cloudMeterMiddleware(req, res, next) {
    const start = Date.now()

    res.on('finish', () => {
      try {
        const route = (req.route && req.route.path) ? req.route.path : req.path
        reporter.report({
          route:       `${req.method} ${route}`,
          method:      req.method,
          status:      res.statusCode,
          durationMs:  Date.now() - start,
          egressBytes: parseInt(res.getHeader('content-length') || '0', 10) || 0,
        })
      } catch (_) {}
    })

    next()
  }
}

module.exports = { cloudMeter }
