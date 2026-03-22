'use strict'

/**
 * CloudMeter — per-endpoint cloud cost monitoring for Node.js web apps.
 *
 * Express:
 *   const { cloudMeter } = require('cloudmeter')
 *   app.use(cloudMeter({ provider: 'AWS', targetUsers: 1000 }))
 *
 * Fastify:
 *   const { cloudMeterPlugin } = require('cloudmeter')
 *   await fastify.register(cloudMeterPlugin, { provider: 'AWS', targetUsers: 1000 })
 *
 * Dashboard: http://localhost:7777
 */

const { cloudMeter }       = require('./express')
const { cloudMeterPlugin } = require('./fastify')
const sidecar              = require('./sidecar')
const reporter             = require('./reporter')

module.exports = { cloudMeter, cloudMeterPlugin, sidecar, reporter }
