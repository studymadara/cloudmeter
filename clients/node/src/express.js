'use strict'

/**
 * Express middleware.
 *
 * Usage:
 *   const { cloudMeter } = require('cloudmeter')
 *   app.use(cloudMeter({ provider: 'AWS', targetUsers: 1000 }))
 *
 * Metrics are buffered in-process. The native cost projector and dashboard
 * server will be wired up in the next iteration.
 */

const reporter = require('./reporter')

let _started = false

function cloudMeter(_opts = {}) {
  if (!_started) {
    _started = true
    reporter.startRecording()
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
