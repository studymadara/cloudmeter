'use strict';
const { CloudMeterReporter } = require('./reporter');

/**
 * Express middleware factory.
 *
 * Usage:
 *   const cloudmeter = require('@cloudmeter/client/express');
 *   app.use(cloudmeter());
 */
function cloudmeterExpress(options = {}) {
  const reporter = new CloudMeterReporter(options);
  return function cloudmeterMiddleware(req, res, next) {
    const start = process.hrtime.bigint();
    res.on('finish', () => {
      try {
        const durationMs = Number(process.hrtime.bigint() - start) / 1_000_000;
        const route = req.route ? req.route.path : req.path;
        const egressBytes = parseInt(res.getHeader('Content-Length') || '0', 10) || 0;
        reporter.record({
          route,
          method: req.method,
          status: res.statusCode,
          durationMs: Math.round(durationMs),
          egressBytes,
        });
      } catch (_) {}
    });
    next();
  };
}

module.exports = cloudmeterExpress;
