'use strict'

// Native Node.js cost projector — port of sidecar-rs/src/projector.rs.
//
// Key adaptation: the Java agent measures cpu_core_seconds via ThreadMXBean.
// Node.js has no equivalent, so durationMs is used as a proxy:
//   cpuCoreSec = durationMs / 1000
// This is a reasonable approximation for synchronous handlers.

const {
  PRICING_DATE,
  HOURS_PER_MONTH,
  GIB_IN_BYTES,
  getInstances,
  getEgressRatePerGib,
} = require('./pricing-catalog')

const SCALE_USERS        = [100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 500000, 1000000]
const RECORDING_WINDOW_S = 300  // 5-minute rolling window (matches Rust sidecar)

// ── project ───────────────────────────────────────────────────────────────────
//
// metrics: array of objects from reporter.getMetrics()
//   { route, method, status, durationMs, egressBytes, ts }
//
// opts:
//   provider                  'AWS' | 'GCP' | 'AZURE'   (default: 'AWS')
//   region                    string                      (default: 'us-east-1')
//   targetUsers               number                      (default: 1000)
//   requestsPerUserPerSecond  number                      (default: 1.0)
//   budgetUsd                 number                      (default: 0 = disabled)
//
// Returns: { projections, warmupCount }
//   projections sorted descending by projectedMonthlyCostUsd
//
function project(metrics, opts = {}) {
  const {
    provider                 = 'AWS',
    targetUsers              = 1000,
    requestsPerUserPerSecond = 1.0,
    budgetUsd                = 0,
  } = opts

  const instances  = getInstances(provider)
  const egressRate = getEgressRatePerGib(provider)

  // Partition warmup vs live using the flag set by reporter.js at record time
  const live        = metrics.filter(m => !m.warmup)
  const warmupCount = metrics.length - live.length

  if (live.length === 0) return { projections: [], warmupCount }

  const totalObservedRps = live.length / RECORDING_WINDOW_S
  const safeRps          = totalObservedRps > 0 ? totalObservedRps : 1.0
  const targetTotalRps   = targetUsers * requestsPerUserPerSecond

  // Group by route
  const byRoute = new Map()
  for (const m of live) {
    if (!byRoute.has(m.route)) byRoute.set(m.route, [])
    byRoute.get(m.route).push(m)
  }

  const projections = []
  for (const [route, entries] of byRoute) {
    projections.push(
      _projectRoute(route, entries, safeRps, targetTotalRps, targetUsers,
        requestsPerUserPerSecond, budgetUsd, instances, egressRate)
    )
  }

  projections.sort((a, b) => b.projectedMonthlyCostUsd - a.projectedMonthlyCostUsd)
  return { projections, warmupCount }
}

// ── computeStats ──────────────────────────────────────────────────────────────
//
// Computes per-request cost distribution (p50/p95/p99) per route.
// Used by the variance panel in the dashboard.
//
function computeStats(metrics, projections, opts = {}) {
  const { provider = 'AWS' } = opts
  const egressRate = getEgressRatePerGib(provider)
  const instances  = getInstances(provider)

  // Map route → hourlyUsd from the recommended instance in projections
  const hourlyByRoute = new Map()
  for (const p of projections) {
    const inst = instances.find(i => i.name === p.recommendedInstance) || instances[0]
    hourlyByRoute.set(p.route, inst.hourlyUsd)
  }

  const live = metrics.filter(m => !m.warmup)

  const byRoute = new Map()
  for (const m of live) {
    if (!byRoute.has(m.route)) byRoute.set(m.route, [])
    byRoute.get(m.route).push(m)
  }

  const stats = []
  for (const [route, entries] of byRoute) {
    const hourlyUsd = hourlyByRoute.get(route) || 0.096  // fallback: m5.large

    // Per-request cost = cpu cost + egress cost
    const costs = entries
      .map(m => {
        const cpuCost    = (m.durationMs / 1000 / 3600) * hourlyUsd
        const egressCost = ((m.egressBytes || 0) / GIB_IN_BYTES) * egressRate
        return cpuCost + egressCost
      })
      .sort((a, b) => a - b)

    const p50   = _percentile(costs, 50)
    const p95   = _percentile(costs, 95)
    const p99   = _percentile(costs, 99)
    const ratio = p50 > 0 ? p95 / p50 : 0

    stats.push({
      route,
      requests:       entries.length,
      p50CostUsd:     p50,
      p95CostUsd:     p95,
      p99CostUsd:     p99,
      varianceRatio:  _round2(ratio),
      varianceWarning: ratio > 1.5,
    })
  }

  stats.sort((a, b) => b.p95CostUsd - a.p95CostUsd)
  return stats
}

// ── internal ──────────────────────────────────────────────────────────────────

function _projectRoute(route, entries, totalObservedRps, targetTotalRps, targetUsers,
  rpu, budgetUsd, instances, egressRate) {

  const observedRps  = entries.length / RECORDING_WINDOW_S
  const scaleFactor  = targetTotalRps / totalObservedRps
  const projectedRps = observedRps * scaleFactor

  // durationMs / 1000 = cpu core-seconds proxy
  const cpuCoreSecs = entries.map(m => m.durationMs / 1000)
  const egressBytes = entries.map(m => m.egressBytes || 0)
  const durations   = entries.map(m => m.durationMs)

  const medianCpu      = _median(cpuCoreSecs)
  const medianEgress   = _median(egressBytes)
  const medianDuration = _median(durations)

  const monthlyCost = _monthlyCost(projectedRps, medianCpu, medianEgress, instances, egressRate)
  const costPerUser = monthlyCost / (targetUsers || 1)
  const recommended = _selectInstance(projectedRps * medianCpu, instances)

  const costCurve = SCALE_USERS.map(users => {
    const scaledTargetRps = users * rpu
    const scaledRps       = observedRps * (scaledTargetRps / totalObservedRps)
    return {
      users,
      monthlyCostUsd: _round2(_monthlyCost(scaledRps, medianCpu, medianEgress, instances, egressRate)),
    }
  })

  return {
    route,
    observedRps:             _round4(observedRps),
    projectedRps:            _round1(projectedRps),
    projectedMonthlyCostUsd: _round2(monthlyCost),
    projectedCostPerUserUsd: _round6(costPerUser),
    recommendedInstance:     recommended.name,
    medianDurationMs:        _round2(medianDuration),
    medianCpuMs:             _round2(medianCpu * 1000),
    exceedsBudget:           budgetUsd > 0 && monthlyCost > budgetUsd,
    costCurve,
  }
}

function _monthlyCost(projectedRps, medianCpuCoreSec, medianEgressBytes, instances, egressRate) {
  const requiredCores  = projectedRps * medianCpuCoreSec
  const inst           = _selectInstance(requiredCores, instances)
  const secondsPerMo   = HOURS_PER_MONTH * 3600
  const egressGibPerMo = projectedRps * medianEgressBytes * secondsPerMo / GIB_IN_BYTES
  return inst.hourlyUsd * HOURS_PER_MONTH + egressGibPerMo * egressRate
}

function _selectInstance(requiredCores, instances) {
  return instances.find(i => i.vCpu >= requiredCores) || instances[instances.length - 1]
}

function _median(arr) {
  if (!arr.length) return 0
  const s = arr.slice().sort((a, b) => a - b)
  const m = Math.floor(s.length / 2)
  return s.length % 2 === 0 ? (s[m - 1] + s[m]) / 2 : s[m]
}

function _percentile(sorted, p) {
  if (!sorted.length) return 0
  const idx = Math.min(Math.floor((p / 100) * sorted.length), sorted.length - 1)
  return sorted[idx]
}

function _round1(v) { return Math.round(v * 10) / 10 }
function _round2(v) { return Math.round(v * 100) / 100 }
function _round4(v) { return Math.round(v * 10000) / 10000 }
function _round6(v) { return Math.round(v * 1_000_000) / 1_000_000 }

module.exports = { project, computeStats, PRICING_DATE, SCALE_USERS }
