'use strict'

/**
 * Fastify plugin.
 *
 * Usage:
 *   const { cloudMeterPlugin } = require('cloudmeter')
 *   await fastify.register(cloudMeterPlugin, { provider: 'AWS', targetUsers: 1000 })
 *
 * Metrics are buffered in-process. The native cost projector and dashboard
 * server will be wired up in the next iteration.
 */

const reporter = require('./reporter')

let _started = false

async function cloudMeterPlugin(fastify, opts) { // eslint-disable-line no-unused-vars
  if (!_started) {
    _started = true
    reporter.startRecording()
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
