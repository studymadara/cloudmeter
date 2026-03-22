'use strict'

/**
 * Binary download and subprocess management.
 * Binary is cached at ~/.cloudmeter/bin/cloudmeter-sidecar.
 * Downloaded automatically from GitHub Releases on first use.
 */

const { spawn } = require('child_process')
const { existsSync, mkdirSync, chmodSync, createWriteStream, renameSync } = require('fs')
const https = require('https')
const http  = require('http')
const os    = require('os')
const path  = require('path')

const GITHUB_REPO = process.env.CLOUDMETER_REPO || 'studymadara/cloudmeter'

function installDir() {
  return path.join(os.homedir(), '.cloudmeter', 'bin')
}

function platformAsset() {
  const plat = os.platform()
  const arch = os.arch()

  let osName, archName, ext = ''
  if      (plat === 'linux')  osName = 'linux'
  else if (plat === 'darwin') osName = 'macos'
  else if (plat === 'win32')  { osName = 'windows'; ext = '.exe' }
  else throw new Error(`[cloudmeter] Unsupported OS: ${plat}`)

  if      (arch === 'x64')   archName = 'x86_64'
  else if (arch === 'arm64') archName = 'arm64'
  else throw new Error(`[cloudmeter] Unsupported CPU architecture: ${arch}`)

  return `cloudmeter-sidecar-${osName}-${archName}${ext}`
}

function binaryPath() {
  const ext = os.platform() === 'win32' ? '.exe' : ''
  return path.join(installDir(), `cloudmeter-sidecar${ext}`)
}

function httpsGet(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { headers: { 'User-Agent': 'cloudmeter-node' } }, (res) => {
      if (res.statusCode === 301 || res.statusCode === 302) {
        return httpsGet(res.headers.location).then(resolve).catch(reject)
      }
      let data = ''
      res.on('data', chunk => { data += chunk })
      res.on('end', () => resolve(data))
      res.on('error', reject)
    })
    req.on('error', reject)
  })
}

function downloadFile(url, dest) {
  return new Promise((resolve, reject) => {
    const follow = (u) => {
      https.get(u, { headers: { 'User-Agent': 'cloudmeter-node' } }, (res) => {
        if (res.statusCode === 301 || res.statusCode === 302) {
          return follow(res.headers.location)
        }
        if (res.statusCode !== 200) {
          return reject(new Error(`[cloudmeter] HTTP ${res.statusCode} downloading binary`))
        }
        const tmp = dest + '.tmp'
        const file = createWriteStream(tmp)
        res.pipe(file)
        file.on('finish', () => {
          file.close(() => {
            renameSync(tmp, dest)
            resolve()
          })
        })
        file.on('error', reject)
      }).on('error', reject)
    }
    follow(url)
  })
}

async function ensureBinary() {
  const binary = binaryPath()
  if (existsSync(binary)) return binary

  const asset = platformAsset()
  const apiUrl = `https://api.github.com/repos/${GITHUB_REPO}/releases/latest`

  let release
  try {
    release = JSON.parse(await httpsGet(apiUrl))
  } catch (e) {
    throw new Error(`[cloudmeter] Could not fetch release info: ${e.message}`)
  }

  const found = (release.assets || []).find(a => a.name === asset)
  if (!found) {
    throw new Error(
      `[cloudmeter] No binary for your platform (${asset}). ` +
      `See https://github.com/${GITHUB_REPO}/releases`
    )
  }

  process.stderr.write(`[cloudmeter] Downloading ${asset} (${release.tag_name})...\n`)
  mkdirSync(path.dirname(binary), { recursive: true })
  await downloadFile(found.browser_download_url, binary)
  chmodSync(binary, 0o755)
  process.stderr.write(`[cloudmeter] Installed to ${binary}\n`)

  return binary
}

// ── Subprocess management ────────────────────────────────────────────────────

let _proc = null

function waitReady(port, attempts = 50) {
  return new Promise((resolve) => {
    let tries = 0
    const check = () => {
      const req = http.get(`http://127.0.0.1:${port}/api/status`, (res) => {
        if (res.statusCode === 200) return resolve()
        res.resume()
        if (++tries < attempts) setTimeout(check, 100)
        else resolve()
      })
      req.on('error', () => {
        if (++tries < attempts) setTimeout(check, 100)
        else resolve()
      })
      req.setTimeout(200, () => req.destroy())
    }
    check()
  })
}

async function start(opts = {}) {
  if (_proc && _proc.exitCode === null) return // already running

  const {
    provider                  = 'AWS',
    region                    = 'us-east-1',
    targetUsers               = 1000,
    requestsPerUserPerSecond  = 1.0,
    budgetUsd                 = 0,
    ingestPort                = 7778,
    dashboardPort             = 7777,
  } = opts

  let binary
  try {
    binary = await ensureBinary()
  } catch (e) {
    process.stderr.write(`${e.message}\n`)
    return // degrade gracefully — app still works, just no cost data
  }

  _proc = spawn(binary, [
    '--provider',                     provider,
    '--region',                       region,
    '--target-users',                 String(targetUsers),
    '--requests-per-user-per-second', String(requestsPerUserPerSecond),
    '--budget-usd',                   String(budgetUsd),
    '--ingest-port',                  String(ingestPort),
    '--dashboard-port',               String(dashboardPort),
  ], { stdio: 'ignore' })

  _proc.on('error', (e) => {
    process.stderr.write(`[cloudmeter] Sidecar process error: ${e.message}\n`)
  })

  // Register cleanup handlers once
  process.once('exit',    stop)
  process.once('SIGINT',  () => { stop(); process.exit(130) })
  process.once('SIGTERM', () => { stop(); process.exit(143) })

  await waitReady(ingestPort)
  process.stderr.write(
    `[cloudmeter] Sidecar ready — dashboard at http://localhost:${dashboardPort}\n`
  )
}

function stop() {
  if (_proc) {
    _proc.kill()
    _proc = null
  }
}

module.exports = { start, stop, ensureBinary, binaryPath }
