'use strict'

/**
 * Fastify plugin.
 *
 * Usage:
 *   const { cloudMeterPlugin } = require('cloudmeter')
 *   await fastify.register(cloudMeterPlugin, { provider: 'AWS', targetUsers: 1000 })
 *
 * Options:
 *   provider                  'AWS' | 'GCP' | 'AZURE'  (default: 'AWS')
 *   region                    string                     (default: 'us-east-1')
 *   targetUsers               number                     (default: 1000)
 *   requestsPerUserPerSecond  number                     (default: 1.0)
 *   budgetUsd                 number                     (default: 0 = disabled)
 *   port                      number                     (default: 7777)
 *
 * On first registration, starts the dashboard server at http://127.0.0.1:<port>.
 */

const reporter        = require('./reporter')
const dashboardServer = require('./dashboard-server')

let _started = false

async function cloudMeterPlugin(fastify, opts) {
  if (!_started) {
    _started = true
    reporter.startRecording()
    dashboardServer.start(opts)
  }

  fastify.addHook('onResponse', async (request, reply) => {
    try {
      // request.routeOptions.url is the Fastify route template e.g. /api/users/:id
      const route = (request.routeOptions && request.routeOptions.url)
        ? request.routeOptions.url
        : request.url
      reporter.report({
        route:       `${request.method} ${route}`,
        method:      request.method,
        status:      reply.statusCode,
        durationMs:  Math.round(reply.elapsedTime),
        egressBytes: parseInt(reply.getHeader('content-length') || '0', 10) || 0,
      })
    } catch (_) {}
  })
}

// Allow registration without a name prefix
cloudMeterPlugin[Symbol.for('skip-override')] = true

module.exports = { cloudMeterPlugin }
