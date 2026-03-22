'use strict';
const http = require('http');

/**
 * Fire-and-forget metric reporter. Posts to the CloudMeter sidecar.
 * Never throws — instrumentation must never crash user code.
 */
class CloudMeterReporter {
  constructor(options = {}) {
    const url = new URL(options.sidecarUrl || 'http://127.0.0.1:7778');
    this._host = url.hostname;
    this._port = parseInt(url.port || '7778', 10);
    this._path = '/api/metrics';
  }

  record({ route, method, status, durationMs, egressBytes = 0 }) {
    const payload = JSON.stringify({
      route: `${method.toUpperCase()} ${route}`,
      method: method.toUpperCase(),
      status,
      durationMs,
      egressBytes,
    });
    this._postAsync(payload);
  }

  _postAsync(payload) {
    try {
      const data = Buffer.from(payload, 'utf-8');
      const req = http.request({
        host: this._host,
        port: this._port,
        path: this._path,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': data.length,
        },
      });
      req.on('error', () => {}); // swallow
      req.setTimeout(2000, () => req.destroy());
      req.write(data);
      req.end();
    } catch (_) {
      // never let reporting crash the app
    }
  }
}

module.exports = { CloudMeterReporter };
