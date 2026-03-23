'use strict'

/**
 * Express middleware.
 *
 * Usage:
 *   const { cloudMeter } = require('cloudmeter')
 *   app.use(cloudMeter({ provider: 'AWS', targetUsers: 1000 }))
 *
 * cloudMeter() starts the sidecar on first call and registers an atexit
 * handler to stop it when the process exits.
 */

const sidecar  = require('./sidecar')
const reporter = require('./reporter')

let _started = false

function cloudMeter(opts = {}) {
  const { ingestPort = 7778, ...rest } = opts
  reporter.setPort(ingestPort)

  if (!_started) {
    _started = true
    sidecar.start({ ingestPort, ...rest }).catch((err) => {
      process.stderr.write(`[cloudmeter] Failed to start sidecar: ${err.message}\n`)
    })
  }

  return function cloudMeterMiddleware(req, res, next) {
    const start = Date.now()

    res.on('finish', () => {
      try {
        // req.route.path is the Express route template e.g. /api/users/:id
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
