'use strict';
const { CloudMeterReporter } = require('./reporter');

/**
 * Fastify plugin.
 *
 * Usage:
 *   const cloudmeter = require('@cloudmeter/client/fastify');
 *   await fastify.register(cloudmeter);
 */
async function cloudmeterFastify(fastify, options) {
  const reporter = new CloudMeterReporter(options);
  fastify.addHook('onResponse', async (request, reply) => {
    try {
      const route = request.routeOptions?.url || request.url;
      const egressBytes = parseInt(reply.getHeader('content-length') || '0', 10) || 0;
      reporter.record({
        route,
        method: request.method,
        status: reply.statusCode,
        durationMs: Math.round(reply.elapsedTime || 0),
        egressBytes,
      });
    } catch (_) {}
  });
}

cloudmeterFastify[Symbol.for('skip-override')] = true;
module.exports = cloudmeterFastify;
