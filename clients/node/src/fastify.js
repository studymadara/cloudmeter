'use strict'

/**
 * Fastify plugin.
 *
 * Usage:
 *   const { cloudMeterPlugin } = require('cloudmeter')
 *   await fastify.register(cloudMeterPlugin, { provider: 'AWS', targetUsers: 1000 })
 */

const sidecar  = require('./sidecar')
const reporter = require('./reporter')

let _started = false

async function cloudMeterPlugin(fastify, opts) {
  const { ingestPort = 7778, ...rest } = opts
  reporter.setPort(ingestPort)

  if (!_started) {
    _started = true
    try {
      await sidecar.start({ ingestPort, ...rest })
    } catch (err) {
      fastify.log.warn(`[cloudmeter] Failed to start sidecar: ${err.message}`)
    }
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
