'use strict'

// Static AWS / GCP / Azure on-demand pricing tables.
// Source: public pricing pages as of PRICING_DATE.
// All instance lists are sorted ascending by hourlyUsd so that
// getInstances() consumers can pick the cheapest fitting one.

const PRICING_DATE    = '2025-01-01'
const HOURS_PER_MONTH = 730
const GIB_IN_BYTES    = 1_073_741_824

// ── AWS EC2 (us-east-1, Linux on-demand) ─────────────────────────────────────

const _AWS = [
  { name: 't3.nano',    vCpu:  2, hourlyUsd: 0.0052 },
  { name: 't3.micro',   vCpu:  2, hourlyUsd: 0.0104 },
  { name: 't3.small',   vCpu:  2, hourlyUsd: 0.0208 },
  { name: 't3.medium',  vCpu:  2, hourlyUsd: 0.0416 },
  { name: 't3.large',   vCpu:  2, hourlyUsd: 0.0832 },
  { name: 'c5.large',   vCpu:  2, hourlyUsd: 0.0850 },
  { name: 'm5.large',   vCpu:  2, hourlyUsd: 0.0960 },
  { name: 'r5.large',   vCpu:  2, hourlyUsd: 0.1260 },
  { name: 't3.xlarge',  vCpu:  4, hourlyUsd: 0.1664 },
  { name: 'c5.xlarge',  vCpu:  4, hourlyUsd: 0.1700 },
  { name: 'm5.xlarge',  vCpu:  4, hourlyUsd: 0.1920 },
  { name: 'r5.xlarge',  vCpu:  4, hourlyUsd: 0.2520 },
  { name: 't3.2xlarge', vCpu:  8, hourlyUsd: 0.3328 },
  { name: 'c5.2xlarge', vCpu:  8, hourlyUsd: 0.3400 },
  { name: 'm5.2xlarge', vCpu:  8, hourlyUsd: 0.3840 },
  { name: 'r5.2xlarge', vCpu:  8, hourlyUsd: 0.5040 },
  { name: 'c5.4xlarge', vCpu: 16, hourlyUsd: 0.6800 },
  { name: 'm5.4xlarge', vCpu: 16, hourlyUsd: 0.7680 },
  { name: 'c5.9xlarge', vCpu: 36, hourlyUsd: 1.5300 },
  { name: 'm5.8xlarge', vCpu: 32, hourlyUsd: 1.5360 },
].sort((a, b) => a.hourlyUsd - b.hourlyUsd)

// ── GCP Compute Engine (us-central1, Linux on-demand) ────────────────────────

const _GCP = [
  { name: 'e2-micro',       vCpu:  2, hourlyUsd: 0.0084 },
  { name: 'e2-small',       vCpu:  2, hourlyUsd: 0.0168 },
  { name: 'e2-medium',      vCpu:  2, hourlyUsd: 0.0335 },
  { name: 'e2-standard-2',  vCpu:  2, hourlyUsd: 0.0671 },
  { name: 'n2-standard-2',  vCpu:  2, hourlyUsd: 0.0971 },
  { name: 'e2-standard-4',  vCpu:  4, hourlyUsd: 0.1341 },
  { name: 'n2-standard-4',  vCpu:  4, hourlyUsd: 0.1942 },
  { name: 'c2-standard-4',  vCpu:  4, hourlyUsd: 0.2088 },
  { name: 'e2-standard-8',  vCpu:  8, hourlyUsd: 0.2683 },
  { name: 'n2-standard-8',  vCpu:  8, hourlyUsd: 0.3883 },
  { name: 'c2-standard-8',  vCpu:  8, hourlyUsd: 0.4176 },
  { name: 'e2-standard-16', vCpu: 16, hourlyUsd: 0.5366 },
  { name: 'n2-standard-16', vCpu: 16, hourlyUsd: 0.7766 },
  { name: 'c2-standard-16', vCpu: 16, hourlyUsd: 0.8352 },
  { name: 'e2-standard-32', vCpu: 32, hourlyUsd: 1.0732 },
  { name: 'c2-standard-30', vCpu: 30, hourlyUsd: 1.5660 },
].sort((a, b) => a.hourlyUsd - b.hourlyUsd)

// ── Azure VM (East US, Linux on-demand) ──────────────────────────────────────

const _AZURE = [
  { name: 'B1ms',    vCpu:  1, hourlyUsd: 0.0207 },
  { name: 'B2s',     vCpu:  2, hourlyUsd: 0.0416 },
  { name: 'B2ms',    vCpu:  2, hourlyUsd: 0.0832 },
  { name: 'F2s_v2',  vCpu:  2, hourlyUsd: 0.0850 },
  { name: 'D2s_v5',  vCpu:  2, hourlyUsd: 0.0960 },
  { name: 'B4ms',    vCpu:  4, hourlyUsd: 0.1664 },
  { name: 'F4s_v2',  vCpu:  4, hourlyUsd: 0.1700 },
  { name: 'D4s_v5',  vCpu:  4, hourlyUsd: 0.1920 },
  { name: 'B8ms',    vCpu:  8, hourlyUsd: 0.3328 },
  { name: 'F8s_v2',  vCpu:  8, hourlyUsd: 0.3400 },
  { name: 'D8s_v5',  vCpu:  8, hourlyUsd: 0.3840 },
  { name: 'B16ms',   vCpu: 16, hourlyUsd: 0.6656 },
  { name: 'F16s_v2', vCpu: 16, hourlyUsd: 0.6800 },
  { name: 'D16s_v5', vCpu: 16, hourlyUsd: 0.7680 },
  { name: 'F32s_v2', vCpu: 32, hourlyUsd: 1.3600 },
  { name: 'D32s_v5', vCpu: 32, hourlyUsd: 1.5360 },
].sort((a, b) => a.hourlyUsd - b.hourlyUsd)

// ── Egress rates ($/GiB outbound, first 10 GiB/month) ────────────────────────

const _EGRESS = { AWS: 0.09, GCP: 0.085, AZURE: 0.087 }

// ── Public API ────────────────────────────────────────────────────────────────

function getInstances(provider) {
  switch ((provider || 'AWS').toUpperCase()) {
    case 'GCP':   return _GCP
    case 'AZURE': return _AZURE
    default:      return _AWS
  }
}

function getEgressRatePerGib(provider) {
  return _EGRESS[(provider || 'AWS').toUpperCase()] ?? 0.09
}

module.exports = { PRICING_DATE, HOURS_PER_MONTH, GIB_IN_BYTES, getInstances, getEgressRatePerGib }
