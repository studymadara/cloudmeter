'use strict';
const { test } = require('node:test');
const assert = require('node:assert');
const { CloudMeterReporter } = require('./reporter');

test('record does not throw when sidecar unreachable', () => {
  const reporter = new CloudMeterReporter({ sidecarUrl: 'http://127.0.0.1:19999' });
  // Should not throw
  reporter.record({ route: '/api/test', method: 'GET', status: 200, durationMs: 10 });
});

test('record uppercases method', () => {
  const reporter = new CloudMeterReporter({ sidecarUrl: 'http://127.0.0.1:19999' });
  const payloads = [];
  reporter._postAsync = (p) => payloads.push(JSON.parse(p));
  reporter.record({ route: '/api/test', method: 'get', status: 200, durationMs: 10 });
  assert.strictEqual(payloads[0].method, 'GET');
  assert.strictEqual(payloads[0].route, 'GET /api/test');
});

test('record includes egressBytes default 0', () => {
  const reporter = new CloudMeterReporter({ sidecarUrl: 'http://127.0.0.1:19999' });
  const payloads = [];
  reporter._postAsync = (p) => payloads.push(JSON.parse(p));
  reporter.record({ route: '/api/test', method: 'GET', status: 200, durationMs: 5 });
  assert.strictEqual(payloads[0].egressBytes, 0);
});
