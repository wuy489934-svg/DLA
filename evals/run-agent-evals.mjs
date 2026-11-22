#!/usr/bin/env node
/**
 * DLA Android Agent 工具层评测脚本（只读/等待/截图）。
 *
 * 连接：http://127.0.0.1:<port>/json/list -> WebSocket -> Runtime.evaluate
 * 工具：AndroidHost.debugTool(name, argsJson)；shell 自检：AndroidHost.shellTest('id')
 *
 * 不会调用 phone_tap / phone_input / phone_open / phone_shell / phone_write 等会改变设备状态的工具。
 */
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url))
const DEFAULT_CASES_PATH = path.join(SCRIPT_DIR, 'cases.json')
const DEFAULT_OUT_DIR = path.join(SCRIPT_DIR, 'results')
const HARNESS_VERSION = '1.0.0'
const MAX_OUTPUT_CHARS = 6000
const DEFAULT_TIMEOUT_MS = 30000
const DEFAULT_CONNECT_TIMEOUT_MS = 5000

const ENV_SKIP_PATTERNS = [
  { pattern: 'Shizuku 未运行', reason: 'Shizuku 未运行（环境依赖）' },
  { pattern: '尚未授予 Shizuku|尚未授权', reason: 'Shizuku 未授权（环境依赖）' },
  {
    pattern: '需要启用无障碍读屏|无障碍读屏未启用|无障碍.*未启用',
    reason: '无障碍读屏未启用（环境依赖）',
  },
]

class InfraError extends Error {
  constructor(message, details) {
    super(message)
    this.name = 'InfraError'
    this.details = details
  }
}

function parseArgs(argv) {
  const opts = {
    host: '127.0.0.1',
    port: Number(process.env.DLA_CDP_PORT || 9223),
    package: process.env.DLA_PACKAGE || 'io.github.dla.agent',
    serial: process.env.ANDROID_SERIAL || '',
    socket: '',
    noAdb: false,
    casesPath: DEFAULT_CASES_PATH,
    outDir: DEFAULT_OUT_DIR,
    jsonPath: '',
    mdPath: '',
    timeout: DEFAULT_TIMEOUT_MS,
    connectTimeout: DEFAULT_CONNECT_TIMEOUT_MS,
    filter: '',
    label: '',
    strict: false,
    verbose: false,
    list: false,
    help: false,
  }
  const take = (index, name) => {
    const value = argv[index + 1]
    if (value === undefined || value.startsWith('--')) {
      throw new Error(`${name} 需要一个值`)
    }
    return value
  }
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    switch (arg) {
      case '--host':
        opts.host = take(index, arg)
        index += 1
        break
      case '--port':
        opts.port = Number(take(index, arg))
        index += 1
        break
      case '--package':
        opts.package = take(index, arg)
        index += 1
        break
      case '--serial':
        opts.serial = take(index, arg)
        index += 1
        break
      case '--socket':
        opts.socket = take(index, arg)
        index += 1
        break
      case '--no-adb':
        opts.noAdb = true
        break
      case '--cases':
        opts.casesPath = path.resolve(take(index, arg))
        index += 1
        break
      case '--out':
        opts.outDir = path.resolve(take(index, arg))
        index += 1
        break
      case '--json':
        opts.jsonPath = path.resolve(take(index, arg))
        index += 1
        break
      case '--md':
        opts.mdPath = path.resolve(take(index, arg))
        index += 1
        break
      case '--timeout':
        opts.timeout = Number(take(index, arg))
        index += 1
        break
      case '--connect-timeout':
        opts.connectTimeout = Number(take(index, arg))
        index += 1
        break
      case '--filter':
        opts.filter = take(index, arg)
        index += 1
        break
      case '--label':
        opts.label = take(index, arg)
        index += 1
        break
      case '--strict':
        opts.strict = true
        break
      case '--verbose':
        opts.verbose = true
        break
      case '--list':
        opts.list = true
        break
      case '--help':
      case '-h':
        opts.help = true
        break
      default:
        throw new Error(`未知参数：${arg}`)
    }
  }
  if (!Number.isFinite(opts.port) || opts.port <= 0 || opts.port > 65535) {
    throw new Error(`--port 无效：${opts.port}`)
  }
  if (!Number.isFinite(opts.timeout) || opts.timeout < 1000) {
    throw new Error(`--timeout 无效：${opts.timeout}`)
  }
  if (!Number.isFinite(opts.connectTimeout) || opts.connectTimeout < 500) {
    throw new Error(`--connect-timeout 无效：${opts.connectTimeout}`)
  }
  return opts
}

function usage() {
  return `DLA Android Agent 工具层评测

用法:
  node mobile/evals/run-agent-evals.mjs [选项]

选项:
  --port <n>               CDP 本地端口，默认 9223（或 DLA_CDP_PORT）
  --host <host>            CDP 主机，默认 127.0.0.1
  --package <id>           目标包名，默认 io.github.dla.agent（或 DLA_PACKAGE）
  --serial <serial>        adb 设备序列号；多设备时必须指定
  --socket <name>          直接指定 WebView socket，如 webview_devtools_remote_22766
  --no-adb                 不自动查找 adb / 不自动 forward，只探测现有 9223
  --cases <file>           用例文件，默认 mobile/evals/cases.json
  --out <dir>              输出目录，默认 mobile/evals/results
  --json <file>            JSON 报告路径；默认 <out>/latest.json
  --md <file>              Markdown 报告路径；默认 <out>/latest.md
  --timeout <ms>           单次 Runtime.evaluate 超时，默认 30000
  --connect-timeout <ms>   探测/连接超时，默认 5000
  --filter <regex>         只运行 id 匹配的用例
  --label <text>           报告标签（例如 "device test"）
  --strict                 跳过或警告也以非 0 退出（2）
  --list                   只列出用例，不连接设备
  --verbose                输出更多细节
  -h, --help               显示帮助

示例:
  node mobile/evals/run-agent-evals.mjs
  node evals/run-agent-evals.mjs --package io.github.dla.agent --label "test device"
  node mobile/evals/run-agent-evals.mjs --no-adb --json results/device-run.json
`
}

function logError(message) {
  console.error(`[eval] ${message}`)
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function firstLine(text, max = 300) {
  const value = String(text ?? '').trim()
  if (!value) return ''
  const line = value.split(/\r?\n/).find(Boolean) || value
  return line.length > max ? `${line.slice(0, max)}…` : line
}

function round(value) {
  return Number.isFinite(value) ? Math.round(value * 100) / 100 : null
}

function safeRegex(pattern, flags = 'm') {
  try {
    return new RegExp(pattern, flags.includes('m') ? flags : `${flags}m`)
  } catch {
    return null
  }
}

function compilePattern(pattern, flags = 'm') {
  const regex = safeRegex(pattern, flags)
  if (!regex) throw new Error(`无效正则：${pattern}`)
  return regex
}

function displayPath(filePath) {
  const relative = path.relative(process.cwd(), filePath)
  return relative && !relative.startsWith('..') ? relative.replaceAll('\\', '/') : filePath
}

function loadCases(filePath) {
  let raw
  try {
    raw = JSON.parse(fs.readFileSync(filePath, 'utf8'))
  } catch (error) {
    throw new Error(`读取用例失败 ${filePath}: ${error.message}`)
  }
  const cases = Array.isArray(raw) ? raw : raw?.cases
  if (!Array.isArray(cases) || cases.length === 0) {
    throw new Error(`用例文件没有 cases 数组：${filePath}`)
  }
  const ids = new Set()
  for (const item of cases) {
    if (!item || typeof item !== 'object') throw new Error('用例必须是对象')
    if (!item.id || typeof item.id !== 'string') throw new Error('用例缺少 id')
    if (ids.has(item.id)) throw new Error(`用例 id 重复：${item.id}`)
    ids.add(item.id)
    item.kind = item.kind || 'tool'
    if (!['tool', 'shell', 'screenshot'].includes(item.kind)) {
      throw new Error(`用例 ${item.id} 的 kind 不支持：${item.kind}`)
    }
    if (item.kind !== 'shell' && !item.tool) {
      throw new Error(`用例 ${item.id} 缺少 tool`)
    }
    if (item.kind === 'screenshot' && !item.tool) item.tool = 'phone_screenshot'
    if (item.kind === 'screenshot' && item.tool !== 'phone_screenshot') {
      throw new Error(`用例 ${item.id} 的 screenshot kind 只支持 phone_screenshot`)
    }
    item.args = item.args && typeof item.args === 'object' ? item.args : {}
    const expect = item.expect || {}
    for (const regex of expect.regex || []) compilePattern(regex.pattern, regex.flags || 'm')
    for (const regex of expect.notRegex || []) compilePattern(regex.pattern, regex.flags || 'm')
    for (const skip of item.skipIf || []) compilePattern(skip.pattern)
    for (const warning of expect.warnings || []) compilePattern(warning.pattern)
    for (const warning of expect.warnIf || []) compilePattern(warning.pattern)
  }
  return cases
}

function materialize(value, vars) {
  if (typeof value === 'string') {
    return value.replace(/\{\{(\w+)\}\}/g, (match, key) =>
      Object.prototype.hasOwnProperty.call(vars, key) ? String(vars[key]) : match
    )
  }
  if (Array.isArray(value)) return value.map((item) => materialize(item, vars))
  if (value && typeof value === 'object') {
    const out = {}
    for (const [key, item] of Object.entries(value)) out[key] = materialize(item, vars)
    return out
  }
  return value
}

function outputInfo(text) {
  const value = String(text ?? '')
  return {
    length: value.length,
    truncated: value.length > MAX_OUTPUT_CHARS,
    sha256: createHash('sha256').update(value).digest('hex').slice(0, 16),
    text:
      value.length > MAX_OUTPUT_CHARS
        ? `${value.slice(0, MAX_OUTPUT_CHARS)}\n…[truncated ${value.length - MAX_OUTPUT_CHARS} chars]`
        : value,
  }
}

async function fetchJson(url, timeoutMs) {
  const startedAt = Date.now()
  try {
    const response = await fetch(url, {
      headers: { accept: 'application/json' },
      signal: AbortSignal.timeout(timeoutMs),
    })
    const text = await response.text()
    let data = null
    try {
      data = JSON.parse(text)
    } catch {
      // 保留 text 供诊断
    }
    return {
      ok: response.ok,
      status: response.status,
      data,
      text,
      error: response.ok ? '' : `HTTP ${response.status}`,
      ms: Date.now() - startedAt,
    }
  } catch (error) {
    return {
      ok: false,
      status: null,
      data: null,
      text: '',
      error: error?.message || String(error),
      ms: Date.now() - startedAt,
    }
  }
}

function summarizeProbe(probe, kind = 'probe') {
  return {
    kind,
    ok: !!probe?.ok,
    status: probe?.status ?? null,
    error: probe?.error || null,
    ms: probe?.ms ?? null,
    pageCount: Array.isArray(probe?.data) ? probe.data.length : null,
  }
}

function findAdb() {
  const candidates = []
  if (process.env.ADB) candidates.push(process.env.ADB)
  for (const envName of ['ANDROID_HOME', 'ANDROID_SDK_ROOT']) {
    const sdk = process.env[envName]
    if (sdk) {
      candidates.push(path.join(sdk, 'platform-tools', 'adb.exe'))
      candidates.push(path.join(sdk, 'platform-tools', 'adb'))
    }
  }
  candidates.push(path.resolve(SCRIPT_DIR, '../../toolchains/android-sdk/platform-tools/adb.exe'))
  candidates.push(path.resolve(SCRIPT_DIR, '../../toolchains/android-sdk/platform-tools/adb'))
  candidates.push('adb')
  for (const candidate of candidates) {
    if (candidate === 'adb') {
      const result = spawnSync('adb', ['version'], { encoding: 'utf8', timeout: 5000, windowsHide: true })
      if (!result.error && result.status === 0) return 'adb'
      continue
    }
    if (fs.existsSync(candidate)) return candidate
  }
  return ''
}

function runAdb(adbPath, serial, args, timeoutMs = 10000) {
  const fullArgs = serial ? ['-s', serial, ...args] : args
  const result = spawnSync(adbPath, fullArgs, {
    encoding: 'utf8',
    timeout: timeoutMs,
    windowsHide: true,
    maxBuffer: 32 * 1024 * 1024,
  })
  return {
    ok: !result.error && result.status === 0,
    status: result.status,
    stdout: String(result.stdout ?? '').trim(),
    stderr: String(result.stderr ?? '').trim(),
    error: result.error ? String(result.error) : '',
  }
}

function parseDevices(output) {
  const devices = []
  for (const line of String(output ?? '').split(/\r?\n/)) {
    if (!line.trim() || line.startsWith('List of devices')) continue
    const match = /^(\S+)\s+(device|offline|unauthorized|authorizing)\b(.*)$/.exec(line)
    if (!match) continue
    devices.push({
      serial: match[1],
      state: match[2],
      detail: match[3].trim(),
    })
  }
  return devices
}

function parseForwards(output) {
  const forwards = []
  for (const line of String(output ?? '').split(/\r?\n/)) {
    const match = /^(\S+)\s+tcp:(\d+)\s+localabstract:(.+)$/.exec(line.trim())
    if (!match) continue
    forwards.push({ serial: match[1], port: Number(match[2]), socket: match[3].trim() })
  }
  return forwards
}

function parseSockets(output) {
  const sockets = new Set()
  for (const line of String(output ?? '').split(/\r?\n/)) {
    const match = /@(webview_devtools_remote_\d+)/.exec(line)
    if (match) sockets.add(match[1])
  }
  return [...sockets]
}

function normalizeSocket(value) {
  return String(value ?? '')
    .trim()
    .replace(/^@/, '')
    .replace(/^localabstract:/, '')
}

function parsePackageInfo(dumpsys) {
  const versionName = /versionName=(\S+)/.exec(dumpsys)?.[1] || ''
  const versionCode = /versionCode=(\d+)/.exec(dumpsys)?.[1] || ''
  const pkgFlags = /pkgFlags=\[([^\]]*)\]/.exec(dumpsys)?.[1]?.trim() || ''
  return {
    versionName,
    versionCode,
    pkgFlags,
    debuggable: /\bDEBUGGABLE\b/.test(pkgFlags),
  }
}

async function inspectAdb(opts) {
  const adbPath = findAdb()
  if (!adbPath) {
    return { available: false, error: '未找到 adb（可用 --no-adb 跳过）' }
  }
  const devicesResult = runAdb(adbPath, '', ['devices', '-l'], 10000)
  if (!devicesResult.ok) {
    return {
      available: false,
      path: adbPath,
      error: `adb devices 失败：${devicesResult.stderr || devicesResult.error || 'unknown'}`,
    }
  }
  const devices = parseDevices(devicesResult.stdout)
  const online = devices.filter((device) => device.state === 'device')
  if (online.length === 0) {
    return { available: false, path: adbPath, devices, error: '没有处于 device 状态的 adb 设备' }
  }
  const serial = opts.serial || online[0].serial
  const selected = online.find((device) => device.serial === serial)
  if (!selected) {
    return { available: false, path: adbPath, devices, serial, error: `找不到 adb 设备：${serial}` }
  }
  const result = {
    available: true,
    path: adbPath,
    serial,
    devices,
    warnings: [],
    forwardList: [],
    sockets: [],
    packagePids: [],
    package: null,
  }
  if (!opts.serial && online.length > 1) {
    result.warnings.push(`检测到 ${online.length} 台设备，默认使用第一台；可用 --serial 指定`)
  }
  const forwardResult = runAdb(adbPath, serial, ['forward', '--list'], 10000)
  if (forwardResult.ok) result.forwardList = parseForwards(forwardResult.stdout)
  const socketResult = runAdb(adbPath, serial, ['shell', 'cat', '/proc/net/unix'], 10000)
  if (socketResult.ok) result.sockets = parseSockets(socketResult.stdout)
  if (opts.package) {
    const pidResult = runAdb(adbPath, serial, ['shell', 'pidof', opts.package], 10000)
    result.packagePids = pidResult.ok
      ? pidResult.stdout.split(/\s+/).filter((item) => /^\d+$/.test(item))
      : []
    const packageResult = runAdb(adbPath, serial, ['shell', 'dumpsys', 'package', opts.package], 15000)
    if (packageResult.ok) {
      result.package = {
        name: opts.package,
        ...parsePackageInfo(packageResult.stdout),
        running: result.packagePids.length > 0,
        pids: result.packagePids,
        hasDevtoolsSocket: result.packagePids.some((pid) =>
          result.sockets.includes(`webview_devtools_remote_${pid}`)
        ),
      }
    } else {
      result.package = {
        name: opts.package,
        error: packageResult.stderr || packageResult.error || 'dumpsys package 失败',
      }
    }
  }
  return result
}

async function ensureForward(opts, adbInfo) {
  if (!adbInfo?.available) {
    return { attempted: false, ok: false, reason: adbInfo?.error || 'adb 不可用' }
  }
  const adbPath = adbInfo.path
  const serial = adbInfo.serial
  const socketResult = runAdb(adbPath, serial, ['shell', 'cat', '/proc/net/unix'], 10000)
  const sockets = socketResult.ok ? parseSockets(socketResult.stdout) : adbInfo.sockets || []
  let packagePids = adbInfo.packagePids || []
  let socket = normalizeSocket(opts.socket)
  let matchedBy = 'explicit'
  if (!socket && opts.package) {
    const pidResult = runAdb(adbPath, serial, ['shell', 'pidof', opts.package], 10000)
    packagePids = pidResult.ok ? pidResult.stdout.split(/\s+/).filter((item) => /^\d+$/.test(item)) : []
    socket = sockets.find((item) => packagePids.some((pid) => item.endsWith(`_${pid}`))) || ''
    matchedBy = 'package-pid'
    if (!socket) {
      return {
        attempted: true,
        ok: false,
        serial,
        package: opts.package,
        packagePids,
        sockets,
        matchedBy,
        reason:
          `包 ${opts.package} 正在运行（pid=${packagePids.join(',') || '无'}），但没有 webview_devtools_remote 抽象 socket。` +
          '调试 WebView 可能未启用（release/non-debuggable 包），或进程刚重启。',
      }
    }
  }
  if (!socket) {
    if (sockets.length === 1) {
      socket = sockets[0]
      matchedBy = 'single-socket'
    } else if (sockets.length === 0) {
      return { attempted: true, ok: false, serial, sockets, reason: '设备上没有 webview_devtools_remote socket' }
    } else {
      return {
        attempted: true,
        ok: false,
        serial,
        sockets,
        reason: `发现多个 WebView socket，无法确定目标：${sockets.join(', ')}；请用 --socket 或 --package 指定`,
      }
    }
  }
  const removeResult = runAdb(adbPath, serial, ['forward', '--remove', `tcp:${opts.port}`], 10000)
  const addResult = runAdb(
    adbPath,
    serial,
    ['forward', `tcp:${opts.port}`, `localabstract:${socket}`],
    10000
  )
  if (!addResult.ok) {
    return {
      attempted: true,
      ok: false,
      serial,
      socket,
      reason: `adb forward 失败：${addResult.stderr || addResult.error || `exit ${addResult.status}`}`,
    }
  }
  await sleep(300)
  const probe = await fetchJson(`http://${opts.host}:${opts.port}/json/list`, opts.connectTimeout)
  return {
    attempted: true,
    ok: !!probe.ok,
    serial,
    socket,
    matchedBy,
    package: opts.package || null,
    packagePids,
    sockets,
    removedExisting: removeResult.ok,
    forwardOutput: addResult.stdout,
    probe: summarizeProbe(probe, 'after-forward'),
    reason: probe.ok ? '' : probe.error || 'forward 后仍无法访问 /json/list',
  }
}

function selectTarget(pages) {
  const candidates = (Array.isArray(pages) ? pages : []).filter((page) => page?.type === 'page')
  const scored = candidates
    .map((page) => {
      const url = String(page.url || '')
      const title = String(page.title || '')
      let score = 0
      if (url.includes('appassets')) score += 100
      if (url.startsWith('file://')) score += 20
      if (url.includes('index.html')) score += 10
      if (title.includes('DeepSeek')) score += 5
      if (/^(devtools|chrome|edge|about):/i.test(url)) score -= 100
      return { page, score }
    })
    .sort((left, right) => right.score - left.score)
  return scored[0]?.score >= 0 ? scored[0].page : null
}

function summarizeTarget(target) {
  if (!target) return null
  return {
    id: target.id,
    type: target.type,
    title: target.title,
    url: target.url,
    description: target.description,
    webSocketDebuggerUrl: target.webSocketDebuggerUrl,
  }
}

class CdpClient {
  constructor(ws) {
    this.ws = ws
    this.nextId = 0
    this.pending = new Map()
    this.closed = false
    ws.onmessage = (event) => this.handleMessage(event)
    ws.onclose = () => this.handleClose('WebSocket 已关闭')
    ws.onerror = () => this.handleClose('WebSocket 错误')
  }

  static async connect(wsUrl, timeoutMs) {
    const ws = new WebSocket(wsUrl)
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        try {
          ws.close()
        } catch {
          // ignore
        }
        reject(new Error(`WebSocket 连接超时（${timeoutMs}ms）`))
      }, timeoutMs)
      ws.onopen = () => {
        clearTimeout(timer)
        resolve()
      }
      ws.onerror = () => {
        clearTimeout(timer)
        reject(new Error('WebSocket 连接失败'))
      }
      ws.onclose = () => {
        clearTimeout(timer)
        reject(new Error('WebSocket 在连上前关闭'))
      }
    })
    const client = new CdpClient(ws)
    await client.send('Runtime.enable', {}, timeoutMs)
    return client
  }

  handleMessage(event) {
    let message
    try {
      message = JSON.parse(String(event.data))
    } catch {
      return
    }
    if (message.id === undefined) return
    const waiter = this.pending.get(message.id)
    if (!waiter) return
    this.pending.delete(message.id)
    clearTimeout(waiter.timer)
    if (message.error) {
      waiter.reject(new Error(`CDP ${message.error.message || JSON.stringify(message.error)}`))
    } else {
      waiter.resolve(message.result)
    }
  }

  handleClose(reason) {
    if (this.closed) return
    this.closed = true
    for (const waiter of this.pending.values()) {
      clearTimeout(waiter.timer)
      waiter.reject(new Error(reason))
    }
    this.pending.clear()
  }

  send(method, params = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
    if (this.closed) return Promise.reject(new Error('WebSocket 已关闭'))
    return new Promise((resolve, reject) => {
      const id = (this.nextId += 1)
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`CDP ${method} 超时（${timeoutMs}ms）`))
      }, timeoutMs)
      this.pending.set(id, { resolve, reject, timer })
      try {
        this.ws.send(JSON.stringify({ id, method, params }))
      } catch (error) {
        clearTimeout(timer)
        this.pending.delete(id)
        reject(error)
      }
    })
  }

  async evaluate(expression, timeoutMs = DEFAULT_TIMEOUT_MS) {
    const response = await this.send(
      'Runtime.evaluate',
      { expression, awaitPromise: true, returnByValue: true },
      timeoutMs
    )
    if (response?.exceptionDetails) {
      const details = response.exceptionDetails
      return {
        ok: false,
        error: details.exception?.description || details.text || 'Runtime.evaluate 异常',
        exceptionDetails: details,
      }
    }
    const result = response?.result || {}
    return { ok: true, value: result.value, type: result.type, description: result.description }
  }

  close() {
    this.closed = true
    try {
      this.ws.close()
    } catch {
      // ignore
    }
    for (const waiter of this.pending.values()) {
      clearTimeout(waiter.timer)
      waiter.reject(new Error('WebSocket 已关闭'))
    }
    this.pending.clear()
  }
}

function parseShizuku(raw) {
  const text = String(raw ?? '').trim()
  if (!text) return { raw: '', parsed: null, error: null }
  try {
    return { raw: text, parsed: JSON.parse(text), error: null }
  } catch (error) {
    return { raw: text, parsed: null, error: error.message }
  }
}

async function probeEnvironment(client, timeoutMs) {
  const expression = `(() => {
    const info = {
      href: location.href,
      title: document.title,
      visibility: document.visibilityState
    };
    const host = (typeof AndroidHost === 'undefined') ? null : AndroidHost;
    info.hasAndroidHost = !!host;
    info.hasDebugTool = !!(host && typeof host.debugTool === 'function');
    info.hasShellTest = !!(host && typeof host.shellTest === 'function');
    if (host && typeof host.shizukuStatus === 'function') {
      try { info.shizukuRaw = String(host.shizukuStatus()); }
      catch (error) { info.shizukuError = String(error); }
    }
    return info;
  })()`
  const result = await client.evaluate(expression, timeoutMs)
  if (!result.ok) throw new Error(`读取 AndroidHost 环境失败：${result.error}`)
  const info = result.value || {}
  if (info.shizukuRaw) {
    const shizuku = parseShizuku(info.shizukuRaw)
    info.shizuku = shizuku.parsed
    if (shizuku.error) info.shizukuParseError = shizuku.error
  }
  return info
}

async function openConnection(opts) {
  const baseUrl = `http://${opts.host}:${opts.port}`
  const details = {
    baseUrl,
    host: opts.host,
    port: opts.port,
    attempts: [],
    adb: null,
    forward: null,
    version: null,
    pages: [],
    target: null,
    health: null,
  }
  let probe = await fetchJson(`${baseUrl}/json/list`, opts.connectTimeout)
  details.attempts.push(summarizeProbe(probe, 'initial'))
  let versionProbe = null
  if (probe.ok) {
    versionProbe = await fetchJson(`${baseUrl}/json/version`, opts.connectTimeout)
    details.version = versionProbe.ok ? versionProbe.data : { error: versionProbe.error }
  }
  if (!opts.noAdb) {
    details.adb = await inspectAdb(opts)
  }
  const versionPackage = versionProbe?.ok ? versionProbe.data?.['Android-Package'] : ''
  const wrongPackage = Boolean(opts.package && versionPackage && versionPackage !== opts.package)
  const emptyPages = probe.ok && Array.isArray(probe.data) && probe.data.length === 0
  if (!opts.noAdb && (!probe.ok || wrongPackage || emptyPages)) {
    details.forward = await ensureForward(opts, details.adb)
    if (details.forward?.attempted) {
      probe = await fetchJson(`${baseUrl}/json/list`, opts.connectTimeout)
      details.attempts.push(summarizeProbe(probe, 'after-forward'))
      if (probe.ok) {
        versionProbe = await fetchJson(`${baseUrl}/json/version`, opts.connectTimeout)
        details.version = versionProbe.ok ? versionProbe.data : { error: versionProbe.error }
      }
    }
  }
  if (!probe.ok) {
    throw new InfraError(
      `无法访问 ${baseUrl}/json/list：${probe.error || '未知错误'}。` +
        '请确认 WebView 调试已开启，并已执行 adb forward。',
      details
    )
  }
  const pages = Array.isArray(probe.data) ? probe.data : []
  details.pages = pages.map((page) => ({
    id: page.id,
    type: page.type,
    title: page.title,
    url: page.url,
  }))
  const finalPackage = versionProbe?.ok ? versionProbe.data?.['Android-Package'] : ''
  if (opts.package && finalPackage && finalPackage !== opts.package) {
    throw new InfraError(
      `CDP 目标是 ${finalPackage}，与期望包 ${opts.package} 不一致；请检查 adb forward 或 --socket。`,
      details
    )
  }
  const target = selectTarget(pages)
  if (!target) {
    throw new InfraError(
      `CDP 已连接，但没有可用的 page target（共 ${pages.length} 个 target）。` +
        '可能 WebView 页面未加载，或当前 forward 指向了错误的进程。',
      details
    )
  }
  details.target = summarizeTarget(target)
  let client
  try {
    client = await CdpClient.connect(target.webSocketDebuggerUrl, opts.connectTimeout)
  } catch (error) {
    throw new InfraError(`连接 WebSocket 失败：${error.message}`, details)
  }
  try {
    const environment = await probeEnvironment(client, opts.timeout)
    details.health = {
      hasAndroidHost: environment.hasAndroidHost,
      hasDebugTool: environment.hasDebugTool,
      hasShellTest: environment.hasShellTest,
      shizuku: environment.shizuku || null,
      shizukuError: environment.shizukuError || null,
      title: environment.title,
      url: environment.href,
      visibility: environment.visibility,
    }
    if (!environment.hasDebugTool) {
      throw new InfraError(
        '当前页面没有 AndroidHost.debugTool；不是 DLA 的 agent 页面，或桥接未注入。',
        details
      )
    }
    return { ok: true, client, details, environment }
  } catch (error) {
    client.close()
    if (error instanceof InfraError) throw error
    throw new InfraError(`读取 AndroidHost 失败：${error.message}`, details)
  }
}

function pageExpressionForTool(name, args) {
  const argsJson = JSON.stringify(args || {})
  return `(() => {
    const t0 = performance.now();
    try {
      const text = AndroidHost.debugTool(${JSON.stringify(name)}, ${JSON.stringify(argsJson)});
      return { ok: true, text: String(text == null ? '' : text), ms: performance.now() - t0 };
    } catch (error) {
      return {
        ok: false,
        text: '',
        error: String(error && error.stack ? error.stack : error),
        ms: performance.now() - t0
      };
    }
  })()`
}

function pageExpressionForShellTest(command) {
  return `(() => {
    const t0 = performance.now();
    try {
      const text = AndroidHost.shellTest(${JSON.stringify(command)});
      return { ok: true, text: String(text == null ? '' : text), ms: performance.now() - t0 };
    } catch (error) {
      return {
        ok: false,
        text: '',
        error: String(error && error.stack ? error.stack : error),
        ms: performance.now() - t0
      };
    }
  })()`
}

async function callTool(client, name, args, timeoutMs) {
  const wallStartedAt = Date.now()
  const result = await client.evaluate(pageExpressionForTool(name, args), timeoutMs)
  const wallMs = Date.now() - wallStartedAt
  if (!result.ok) {
    return { ok: false, text: '', error: result.error, pageMs: null, wallMs }
  }
  const value = result.value || {}
  return {
    ok: value.ok !== false,
    text: String(value.text ?? ''),
    error: value.error ? String(value.error) : '',
    pageMs: Number.isFinite(value.ms) ? value.ms : null,
    wallMs,
  }
}

async function callShellTest(client, timeoutMs) {
  const wallStartedAt = Date.now()
  const result = await client.evaluate(pageExpressionForShellTest('id'), timeoutMs)
  const wallMs = Date.now() - wallStartedAt
  if (!result.ok) {
    return { ok: false, text: '', error: result.error, pageMs: null, wallMs }
  }
  const value = result.value || {}
  return {
    ok: value.ok !== false,
    text: String(value.text ?? ''),
    error: value.error ? String(value.error) : '',
    pageMs: Number.isFinite(value.ms) ? value.ms : null,
    wallMs,
  }
}

function parseScreenshot(text) {
  const pathMatch = /截图已保存：([^\n]+)/.exec(text)
  const dimsMatch = /截图 (\d+)x(\d+)，实际屏幕 (\d+)x(\d+)/.exec(text)
  const jpegMatch = /JPEG (\d+) bytes/.exec(text)
  if (!pathMatch || !dimsMatch) return null
  const shotWidth = Number(dimsMatch[1])
  const shotHeight = Number(dimsMatch[2])
  const screenWidth = Number(dimsMatch[3])
  const screenHeight = Number(dimsMatch[4])
  return {
    path: pathMatch[1].trim(),
    shotWidth,
    shotHeight,
    screenWidth,
    screenHeight,
    jpegBytes: jpegMatch ? Number(jpegMatch[1]) : null,
    scale: shotWidth > 0 ? Math.round((screenWidth / shotWidth) * 1000) / 1000 : null,
  }
}

function parseKeyValues(text) {
  const values = {}
  for (const line of String(text ?? '').split(/\r?\n/)) {
    const match = /^([a-z_]+):\s*(.*)$/.exec(line.trim())
    if (match) values[match[1]] = match[2]
  }
  return values
}

function decorate(caseDef, text, raw) {
  const details = {}
  const tool = caseDef.tool || ''
  if (tool === 'phone_device') {
    details.device = parseKeyValues(text)
  } else if (tool === 'phone_setting_get') {
    const value = String(text ?? '').trim()
    details.setting = {
      namespace: caseDef.args?.namespace || '',
      key: caseDef.args?.key || '',
      value,
      isNull: value === '（null）',
      numeric: /^-?\d+$/.test(value),
    }
  } else if (tool === 'phone_wait') {
    details.wait = {
      requestedMs: caseDef.args?.ms ?? null,
      reported: firstLine(text, 200),
    }
  } else if (tool === 'phone_screen') {
    const source = String(text).startsWith('source: fallback') ? 'fallback' : 'accessibility'
    details.screen = {
      source,
      lineCount: String(text).split(/\r?\n/).filter(Boolean).length,
    }
  } else if (tool === 'phone_files') {
    const lines = String(text).split(/\r?\n/).filter(Boolean)
    let mode = 'unknown'
    if (String(text).includes('（空目录）')) mode = 'native-empty'
    else if (lines.some((line) => /^total\s+\d+/.test(line)) || lines.some((line) => /^[d-][rwx-]{9}\s/.test(line))) {
      mode = 'shell-ls'
    } else if (lines.some((line) => /^[d-]\s+/.test(line))) {
      mode = 'native'
    }
    details.files = {
      mode,
      lineCount: lines.length,
      directoryCount: lines.filter((line) => /^(d\s|d[rwx-]{9}\s)/.test(line)).length,
      fileCount: lines.filter((line) => /^(-\s|-[rwx-]{9}\s)/.test(line)).length,
    }
  } else if (tool === 'phone_read') {
    const sizeMatch = /size=(\d+) bytes/.exec(text)
    const offsetMatch = /offset=(\d+)/.exec(text)
    details.read = {
      sizeBytes: sizeMatch ? Number(sizeMatch[1]) : null,
      offset: offsetMatch ? Number(offsetMatch[1]) : null,
      hasNotFoundText: /No such file or directory|无法读取文件大小|not found/i.test(text),
    }
  } else if (tool === 'phone_file_info') {
    details.fileInfo = {
      kind: /directory/.test(text) ? 'directory' : /\d+\s+bytes/.test(text) ? 'file' : 'unknown',
      hasNotFoundText: /No such file or directory|not found/i.test(text),
    }
  } else if (caseDef.kind === 'screenshot') {
    const parsed = parseScreenshot(text)
    if (parsed) details.screenshot = parsed
  } else if (caseDef.kind === 'shell') {
    const uidMatch = /\buid=(\d+)/.exec(text)
    details.shell = {
      success: String(text).trimStart().startsWith('✅'),
      uid: uidMatch ? Number(uidMatch[1]) : null,
    }
  }
  if (raw?.error) details.pageError = raw.error
  return details
}

function matchSkip(caseDef, text) {
  const patterns = [...(caseDef.skipIf || [])]
  if (caseDef.allowEnvironmentSkip) patterns.push(...ENV_SKIP_PATTERNS)
  for (const item of patterns) {
    const regex = safeRegex(item.pattern)
    if (regex && regex.test(text)) return item
  }
  return null
}

function extraChecks(caseDef, text) {
  const checks = []
  const tool = caseDef.tool || ''
  if (tool === 'phone_files') {
    const systemError = /(^|\n)(ls:|stat:|head:)?[^\n]*(No such file or directory|Permission denied|Operation not permitted)/m.exec(
      text
    )
    checks.push({
      name: 'listing-no-system-error-text',
      ok: !systemError,
      detail: systemError ? firstLine(systemError[0]) : '未发现系统错误文本',
    })
  }
  if (tool === 'phone_read' && (caseDef.expect?.mode || 'success') === 'success') {
    const notFound = /No such file or directory/.test(text)
    checks.push({
      name: 'read-content-present',
      ok: !notFound,
      detail: notFound ? '返回了 No such file or directory' : '读取到内容',
    })
  }
  if (tool === 'phone_file_info') {
    const notFound = /No such file or directory/.test(text)
    checks.push({
      name: 'file-info-target-present',
      ok: !notFound,
      detail: notFound ? '返回了 No such file or directory' : '文件信息可读',
    })
  }
  if (caseDef.kind === 'screenshot') {
    const parsed = parseScreenshot(text)
    checks.push({ name: 'screenshot-path', ok: !!parsed?.path, detail: parsed?.path || '缺少路径' })
    if (!parsed) {
      checks.push({ name: 'screenshot-dims', ok: false, detail: '缺少截图尺寸' })
    } else {
      checks.push({
        name: 'screenshot-dims-positive',
        ok: parsed.shotWidth > 0 && parsed.shotHeight > 0 && parsed.screenWidth > 0 && parsed.screenHeight > 0,
        detail: `${parsed.shotWidth}x${parsed.shotHeight} / ${parsed.screenWidth}x${parsed.screenHeight}`,
      })
      checks.push({
        name: 'screenshot-scaled-bounds',
        ok:
          parsed.shotWidth <= parsed.screenWidth &&
          parsed.shotHeight <= parsed.screenHeight &&
          Math.max(parsed.shotWidth, parsed.shotHeight) <= 1280,
        detail: `截图最长边 ${Math.max(parsed.shotWidth, parsed.shotHeight)}，实际屏幕 ${parsed.screenWidth}x${parsed.screenHeight}`,
      })
      checks.push({
        name: 'screenshot-jpeg-bytes',
        ok: Number.isFinite(parsed.jpegBytes) && parsed.jpegBytes > 0,
        detail: parsed.jpegBytes == null ? '缺少 JPEG 字节数' : `${parsed.jpegBytes} bytes`,
      })
    }
  }
  return checks
}

function evaluateCase(caseDef, raw) {
  const text = String(raw?.text ?? '')
  const trimmed = text.trim()
  const failurePrefix = trimmed.startsWith('错误：')
  const shellFailure = text.includes('❌')
  const failed = !raw?.ok || failurePrefix || shellFailure
  const checks = []
  const warnings = []
  const add = (name, ok, detail = '') => {
    const item = { name, ok: !!ok, detail: String(detail ?? '') }
    checks.push(item)
    return item
  }
  const finish = (status, reason) => ({
    status,
    reason,
    checks,
    warnings,
    details: decorate(caseDef, text, raw),
  })

  if (failed) {
    const skip = matchSkip(caseDef, text)
    if (skip) {
      return finish('skip', `${skip.reason}（命中：${skip.pattern}）`)
    }
  }
  if (!raw?.ok && !text) {
    add('invoke', false, raw?.error || 'AndroidHost 调用失败')
    return finish('fail', `工具调用失败：${firstLine(raw?.error || '未知错误')}`)
  }

  if (caseDef.kind === 'shell') {
    const success = trimmed.startsWith('✅') && !shellFailure
    add('shell-success', success, firstLine(text) || '（空输出）')
    if (!success) {
      return finish('fail', `shellTest 未返回成功：${firstLine(text) || raw?.error || '空输出'}`)
    }
    const uidMatch = /\buid=\d+/.exec(text)
    add('shell-uid', !!uidMatch, uidMatch ? uidMatch[0] : '未找到 uid=...')
  } else {
    const mode = caseDef.expect?.mode || 'success'
    if (mode === 'success') {
      add('not-error', !failed, failed ? firstLine(text) : '输出未以 错误： 开头')
      if (failed) return finish('fail', `工具返回错误：${firstLine(text)}`)
    } else if (mode === 'error') {
      add('error-prefix', failurePrefix, failurePrefix ? '以 错误： 开头' : firstLine(text) || '（空输出）')
      if (!failurePrefix) {
        return finish('fail', `期望明确的 错误： 前缀，实际：${firstLine(text) || '（空输出）'}`)
      }
    } else if (mode === 'failure_text') {
      const markers = caseDef.expect?.failureMarkers || []
      const hit = markers.find((marker) => text.includes(marker))
      add('failure-marker', !!hit, hit ? `命中 ${hit}` : `需要包含：${markers.join(' / ')}`)
      if (!hit) {
        return finish('fail', `失败文本未包含可辨识的失败标记：${firstLine(text) || '（空输出）'}`)
      }
    }
  }

  const expect = caseDef.expect || {}
  for (const expected of expect.contains || []) {
    add(`contains:${expected}`, text.includes(expected), text.includes(expected) ? '已包含' : `缺少：${expected}`)
  }
  if (Array.isArray(expect.containsAny)) {
    const hit = expect.containsAny.find((item) => text.includes(item))
    add(
      'contains-any',
      !!hit,
      hit ? `命中 ${hit}` : `需要至少包含一个：${expect.containsAny.join(' / ')}`
    )
  }
  for (const expected of expect.notContains || []) {
    add(`not-contains:${expected}`, !text.includes(expected), text.includes(expected) ? `不应包含：${expected}` : '未包含')
  }
  for (const item of expect.regex || []) {
    const regex = compilePattern(item.pattern, item.flags || 'm')
    add(`regex:${item.name || item.pattern}`, regex.test(text), regex.test(text) ? '匹配' : `不匹配：${item.pattern}`)
  }
  for (const item of expect.notRegex || []) {
    const regex = compilePattern(item.pattern, item.flags || 'm')
    add(
      `not-regex:${item.name || item.pattern}`,
      !regex.test(text),
      regex.test(text) ? `不应匹配：${item.pattern}` : '未匹配'
    )
  }
  if (expect.minLength != null) {
    add('min-length', text.length >= expect.minLength, `长度 ${text.length}，要求 >= ${expect.minLength}`)
  }
  if (expect.minPageMs != null) {
    const value = raw?.pageMs
    add(
      'min-page-ms',
      Number.isFinite(value) && value >= expect.minPageMs,
      `pageMs=${value == null ? 'null' : round(value)}，要求 >= ${expect.minPageMs}`
    )
  }
  if (expect.maxPageMs != null) {
    const value = raw?.pageMs
    add(
      'max-page-ms',
      Number.isFinite(value) && value <= expect.maxPageMs,
      `pageMs=${value == null ? 'null' : round(value)}，要求 <= ${expect.maxPageMs}`
    )
  }
  for (const check of extraChecks(caseDef, text)) checks.push(check)
  for (const warning of expect.warnings || []) {
    const regex = compilePattern(warning.pattern)
    if (!regex.test(text)) {
      warnings.push({ name: warning.name || 'warning', message: warning.message || warning.name || '警告' })
    }
  }
  for (const warning of expect.warnIf || []) {
    const regex = compilePattern(warning.pattern)
    if (regex.test(text)) {
      warnings.push({ name: warning.name || 'warning', message: warning.message || warning.name || '警告' })
    }
  }
  const failedCheck = checks.find((check) => !check.ok)
  if (failedCheck) {
    return finish('fail', failedCheck.detail || `断言失败：${failedCheck.name}`)
  }
  return finish('pass', warnings.length ? '通过（有警告）' : '全部断言通过')
}

async function verifyScreenshot(client, caseDef, evaluation, raw, opts) {
  const parsed = parseScreenshot(raw.text)
  if (!parsed) return evaluation
  const absolutePath = parsed.path.startsWith('/')
    ? parsed.path
    : `/sdcard/${parsed.path.replace(/^\/+/, '')}`
  const details = {
    ...(evaluation.details || {}),
    screenshot: {
      ...(evaluation.details?.screenshot || {}),
      ...parsed,
      absolutePath,
    },
  }
  const checks = [...(evaluation.checks || [])]
  const warnings = [...(evaluation.warnings || [])]
  let status = evaluation.status
  let reason = evaluation.reason
  let verify
  try {
    verify = await callTool(client, 'phone_file_info', { path: absolutePath }, caseDef.timeoutMs || opts.timeout)
  } catch (error) {
    verify = { ok: false, text: '', error: String(error?.message || error) }
  }
  if (
    verify?.ok &&
    verify.text &&
    !/^错误：/.test(verify.text.trim()) &&
    !/No such file or directory|not found/i.test(verify.text)
  ) {
    const hasSize = /\d+\s+bytes|directory/.test(verify.text)
    checks.push({
      name: 'screenshot.path-exists',
      ok: hasSize,
      detail: firstLine(verify.text, 400),
    })
    if (!hasSize) {
      status = 'fail'
      reason = '截图返回路径，但 phone_file_info 未返回字节数/目录信息'
    }
    details.screenshot.pathVerification = {
      ok: hasSize,
      text: verify.text.slice(0, 1000),
      pageMs: verify.pageMs ?? null,
      wallMs: verify.wallMs ?? null,
    }
  } else {
    const message = firstLine(verify?.text || verify?.error || '无响应', 400)
    if (/No such file or directory|not found/i.test(message)) {
      checks.push({ name: 'screenshot.path-exists', ok: false, detail: message })
      status = 'fail'
      reason = `截图声称已保存，但文件不存在：${message}`
    } else {
      warnings.push({
        name: 'screenshot.path-verification',
        message: `截图路径存在性未能验证（不影响尺寸断言）：${message}`,
      })
      details.screenshot.pathVerification = {
        ok: null,
        error: message,
        pageMs: verify?.pageMs ?? null,
        wallMs: verify?.wallMs ?? null,
      }
    }
  }
  return { ...evaluation, status, reason, checks, warnings, details }
}

async function runCase(client, caseDef, opts, environment) {
  const startedAt = Date.now()
  const def = materialize(caseDef, { runId: opts.runId })
  const tool = def.tool || (def.kind === 'screenshot' ? 'phone_screenshot' : '')
  const record = {
    id: def.id,
    title: def.title || def.id,
    category: def.category || 'uncategorized',
    kind: def.kind,
    tool: tool || null,
    args: def.args || {},
    status: 'fail',
    reason: '',
    warnings: [],
    checks: [],
    details: {},
    output: null,
    timing: { pageMs: null, wallMs: null, totalMs: null },
  }
  if (def.kind === 'shell' && !environment.hasShellTest) {
    record.status = 'skip'
    record.reason = 'AndroidHost.shellTest 不存在（接口缺失）'
    record.timing.totalMs = Date.now() - startedAt
    return record
  }
  let raw
  try {
    raw =
      def.kind === 'shell'
        ? await callShellTest(client, def.timeoutMs || opts.timeout)
        : await callTool(client, tool, def.args || {}, def.timeoutMs || opts.timeout)
  } catch (error) {
    raw = { ok: false, text: '', error: String(error?.message || error), pageMs: null, wallMs: null }
  }
  record.timing = {
    pageMs: raw.pageMs == null ? null : round(raw.pageMs),
    wallMs: raw.wallMs == null ? null : round(raw.wallMs),
    totalMs: Date.now() - startedAt,
  }
  record.output = {
    ...outputInfo(raw.text),
    pageOk: !!raw.ok,
    pageError: raw.error || null,
  }
  if (!raw.ok && !raw.text) {
    record.status = 'fail'
    record.reason = `工具调用失败：${firstLine(raw.error || '未知错误')}`
    record.checks = [{ name: 'invoke', ok: false, detail: raw.error || 'AndroidHost 调用失败' }]
    record.details = { pageError: raw.error || null }
    return record
  }
  let evaluation = evaluateCase(def, raw)
  if (def.kind === 'screenshot' && evaluation.status === 'pass') {
    evaluation = await verifyScreenshot(client, def, evaluation, raw, opts)
  }
  record.status = evaluation.status
  record.reason = evaluation.reason
  record.warnings = evaluation.warnings
  record.checks = evaluation.checks
  record.details = evaluation.details
  if (def.privacy && record.status === 'pass') {
    record.output.text = `[omitted: ${def.privacy}; original ${record.output.length} chars, sha256 ${record.output.sha256}]`
    record.output.redacted = true
  }
  return record
}

function stats(values) {
  const sorted = values.filter((value) => Number.isFinite(value)).sort((left, right) => left - right)
  if (sorted.length === 0) return { min: null, median: null, p95: null, max: null, avg: null }
  const at = (index) => sorted[Math.min(sorted.length - 1, Math.max(0, index))]
  const median = sorted.length % 2
    ? at((sorted.length - 1) / 2)
    : (at(sorted.length / 2 - 1) + at(sorted.length / 2)) / 2
  const p95Index = Math.ceil(sorted.length * 0.95) - 1
  const sum = sorted.reduce((total, value) => total + value, 0)
  return {
    min: round(sorted[0]),
    median: round(median),
    p95: round(at(p95Index)),
    max: round(sorted[sorted.length - 1]),
    avg: round(sum / sorted.length),
  }
}

function computeSummary(results) {
  const counts = { total: results.length, passed: 0, failed: 0, skipped: 0, warned: 0, errors: 0 }
  const byCategory = {}
  const timingGroups = {}
  for (const item of results) {
    if (item.status === 'pass') counts.passed += 1
    else if (item.status === 'skip') counts.skipped += 1
    else if (item.status === 'error') counts.errors += 1
    else counts.failed += 1
    if (item.warnings?.length) counts.warned += 1
    const category = item.category || 'uncategorized'
    byCategory[category] ||= { total: 0, passed: 0, failed: 0, skipped: 0, warned: 0 }
    byCategory[category].total += 1
    if (item.status === 'pass') byCategory[category].passed += 1
    else if (item.status === 'skip') byCategory[category].skipped += 1
    else byCategory[category].failed += 1
    if (item.warnings?.length) byCategory[category].warned += 1
    if (item.timing?.pageMs != null) {
      const key = item.id.startsWith('failure.unknown_tool')
        ? 'unknown-tool'
        : item.kind === 'shell'
          ? 'shell'
          : item.tool || item.kind || 'unknown'
      timingGroups[key] ||= { tool: key, calls: 0, passed: 0, failed: 0, skipped: 0, pageMs: [], wallMs: [] }
      timingGroups[key].calls += 1
      if (item.status === 'pass') timingGroups[key].passed += 1
      else if (item.status === 'skip') timingGroups[key].skipped += 1
      else timingGroups[key].failed += 1
      if (Number.isFinite(item.timing.pageMs)) timingGroups[key].pageMs.push(item.timing.pageMs)
      if (Number.isFinite(item.timing.wallMs)) timingGroups[key].wallMs.push(item.timing.wallMs)
    }
  }
  const timingByTool = {}
  for (const [key, group] of Object.entries(timingGroups)) {
    timingByTool[key] = {
      tool: key,
      calls: group.calls,
      passed: group.passed,
      failed: group.failed,
      skipped: group.skipped,
      pageMs: stats(group.pageMs),
      wallMs: stats(group.wallMs),
    }
  }
  const slowest = results
    .filter((item) => item.timing?.pageMs != null)
    .sort((left, right) => (right.timing.pageMs || 0) - (left.timing.pageMs || 0))[0]
  return {
    ...counts,
    byCategory,
    timingByTool,
    slowest: slowest
      ? { id: slowest.id, tool: slowest.tool, pageMs: slowest.timing.pageMs, wallMs: slowest.timing.wallMs }
      : null,
  }
}

function finalizeReport(report, opts) {
  const summary = computeSummary(report.cases)
  report.summary = summary
  report.ok = summary.failed === 0 && summary.errors === 0
  if (!report.ok) {
    report.verdict = 'fail'
  } else if (summary.skipped > 0 || summary.warned > 0) {
    report.verdict = 'pass_with_skips'
  } else {
    report.verdict = 'pass'
  }
  if (opts.strict && report.ok && (summary.skipped > 0 || summary.warned > 0)) {
    report.ok = false
    report.verdict = 'strict_fail'
  }
  const finished = new Date()
  report.finishedAt = finished.toISOString()
  report.finishedAtLocal = finished.toLocaleString('zh-CN', { hour12: false })
  const started = Date.parse(report.startedAt)
  report.durationMs = Number.isFinite(started) ? finished.getTime() - started : null
}

function mdCell(value, max = 180) {
  return String(value ?? '')
    .replace(/\r?\n/g, '<br>')
    .replace(/\|/g, '\\|')
    .slice(0, max)
}

function statusLabel(item) {
  if (item.status === 'skip') return 'SKIP'
  if (item.status === 'fail') return 'FAIL'
  if (item.status === 'error') return 'ERROR'
  return item.warnings?.length ? 'PASS ⚠' : 'PASS'
}

function verdictLabel(report) {
  switch (report.verdict) {
    case 'pass':
      return '✅ PASS'
    case 'pass_with_skips':
      return '✅ PASS（含跳过/警告）'
    case 'strict_fail':
      return '⚠️ STRICT FAIL（严格模式下跳过/警告非 0）'
    case 'fail':
      return '❌ FAIL'
    case 'infra_error':
      return '⛔ INFRA ERROR'
    default:
      return report.verdict
  }
}

function fmtMs(value) {
  return Number.isFinite(value) ? `${round(value)} ms` : '—'
}

function renderMarkdown(report) {
  const lines = []
  lines.push('# DLA Android Agent 工具层评测报告')
  lines.push('')
  lines.push(`- 结论：**${verdictLabel(report)}**`)
  lines.push(`- 运行时间：${report.startedAtLocal || report.startedAt} → ${report.finishedAtLocal || report.finishedAt || '—'}（本地）；UTC ${report.startedAt} → ${report.finishedAt || '—'}（${fmtMs(report.durationMs)}）`)
  lines.push(`- 标签：${report.label || '（无）'}`)
  lines.push(`- 命令：\`${report.command}\``)
  lines.push(`- Harness：v${report.harness.version} / Node ${report.harness.node} / ${report.harness.platform}`)
  lines.push('')
  if (report.error) {
    lines.push('## 失败原因（连接/环境）')
    lines.push('')
    lines.push('```text')
    lines.push(String(report.error.message || report.error).replace(/```/g, "'''"))
    lines.push('```')
    lines.push('')
  }
  lines.push('## 环境')
  lines.push('')
  lines.push('| 项 | 值 |')
  lines.push('| --- | --- |')
  const connection = report.connection || {}
  const target = connection.target
  const health = connection.health || {}
  lines.push(`| CDP | ${mdCell(connection.baseUrl || '—')} |`)
  lines.push(`| 页面 | ${mdCell(target ? `${target.title || '（无标题）'} — ${target.url || ''}` : '—', 300)} |`)
  lines.push(`| AndroidHost.debugTool | ${health.hasDebugTool ? 'yes' : 'no/未知'} |`)
  lines.push(`| AndroidHost.shellTest | ${health.hasShellTest ? 'yes' : 'no/未知'} |`)
  if (health.shizuku) {
    const shizuku = health.shizuku
    lines.push(
      `| Shizuku | running=${shizuku.running} granted=${shizuku.granted} installed=${shizuku.installed} version=${mdCell(shizuku.version || '—')} |`
    )
  }
  const adb = connection.adb
  if (adb) {
    lines.push(`| ADB | ${mdCell(adb.path || '—')} / ${mdCell(adb.serial || '—')} |`)
    if (adb.package) {
      lines.push(
        `| 目标包 | ${mdCell(adb.package.name || '—')} version=${mdCell(adb.package.versionName || '—')} debuggable=${adb.package.debuggable} pid=${(adb.package.pids || []).join(',') || '—'} |`
      )
    }
    if (connection.forward) {
      lines.push(
        `| forward | ${connection.forward.socket ? `tcp:${report.args.port} → ${connection.forward.socket}` : mdCell(connection.forward.reason || '—', 300)} |`
      )
    } else {
      const currentForward = (adb.forwardList || []).find((item) => item.port === report.args.port)
      if (currentForward) {
        lines.push(`| forward | tcp:${report.args.port} → ${currentForward.socket}（已存在） |`)
      }
    }
  }
  lines.push(
    `| 用例 | ${report.summary.total} 总数 / ${report.summary.passed} 通过 / ${report.summary.failed} 失败 / ${report.summary.skipped} 跳过 / ${report.summary.warned} 警告 |`
  )
  lines.push('')

  lines.push('## 用例结果')
  lines.push('')
  lines.push('| # | 用例 | 工具/接口 | 状态 | page ms | wall ms | 说明 |')
  lines.push('| ---: | --- | --- | --- | ---: | ---: | --- |')
  report.cases.forEach((item, index) => {
    const note = item.status === 'fail' ? item.reason : item.status === 'skip' ? item.reason : item.warnings?.[0]?.message || ''
    lines.push(
      `| ${index + 1} | ${mdCell(item.id, 80)}<br>${mdCell(item.title, 80)} | ${mdCell(item.tool || item.kind, 50)} | ${statusLabel(item)} | ${fmtMs(item.timing?.pageMs)} | ${fmtMs(item.timing?.wallMs)} | ${mdCell(note, 260)} |`
    )
  })
  lines.push('')

  const failures = report.cases.filter((item) => item.status === 'fail' || item.status === 'error')
  const skips = report.cases.filter((item) => item.status === 'skip')
  const warnings = report.cases.filter((item) => item.warnings?.length)
  if (failures.length) {
    lines.push('## 失败详情')
    lines.push('')
    for (const item of failures) {
      lines.push(`### ❌ ${item.id} — ${item.title}`)
      lines.push('')
      lines.push(`- 原因：${item.reason || '（无）'}`)
      const failedChecks = (item.checks || []).filter((check) => !check.ok)
      for (const check of failedChecks) {
        lines.push(`- 断言失败 \`${check.name}\`：${check.detail || ''}`)
      }
      if (item.output?.text) {
        lines.push('')
        lines.push('```text')
        lines.push(String(item.output.text).replace(/```/g, "'''"))
        lines.push('```')
      }
      lines.push('')
    }
  }
  if (skips.length) {
    lines.push('## 跳过')
    lines.push('')
    for (const item of skips) {
      lines.push(`- **${item.id}**：${item.reason || '（无原因）'}`)
    }
    lines.push('')
  }
  if (warnings.length) {
    lines.push('## 警告')
    lines.push('')
    for (const item of warnings) {
      for (const warning of item.warnings) {
        lines.push(`- **${item.id}** \`${warning.name}\`：${warning.message}`)
      }
    }
    lines.push('')
  }

  lines.push('## 工具耗时统计')
  lines.push('')
  lines.push('| 工具 | 调用 | min | median | p95 | max | avg | wall avg |')
  lines.push('| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
  const timingEntries = Object.values(report.summary.timingByTool || {})
  if (timingEntries.length === 0) {
    lines.push('| — | 0 | — | — | — | — | — | — |')
  } else {
    for (const group of timingEntries.sort((left, right) => right.calls - left.calls)) {
      lines.push(
        `| ${mdCell(group.tool, 40)} | ${group.calls} | ${fmtMs(group.pageMs.min)} | ${fmtMs(group.pageMs.median)} | ${fmtMs(group.pageMs.p95)} | ${fmtMs(group.pageMs.max)} | ${fmtMs(group.pageMs.avg)} | ${fmtMs(group.wallMs.avg)} |`
      )
    }
  }
  lines.push('')
  if (report.summary.slowest) {
    lines.push(
      `最慢用例：**${report.summary.slowest.id}**（${report.summary.slowest.tool || '—'}，page ${fmtMs(report.summary.slowest.pageMs)}，wall ${fmtMs(report.summary.slowest.wallMs)}）。`
    )
    lines.push('')
  }

  lines.push('## 说明')
  lines.push('')
  lines.push('- page ms 是页面内 `performance.now()` 测得的 `AndroidHost.debugTool`/`shellTest` 执行时间；wall ms 额外包含 CDP 往返。')
  lines.push('- 本评测只调用只读工具、`phone_wait` 和 `phone_screenshot`；不点击、不输入、不打开应用、不写文件、不改设置。')
  lines.push('- `phone_screenshot` 会写入一张相册截图，这是唯一预期的设备侧副作用。')
  lines.push('- 截图/屏幕工具依赖无障碍；Shizuku 相关工具依赖运行中的 Shizuku。依赖缺失会记录为 SKIP，而不是伪造 PASS。')
  lines.push('- `AndroidHost.debugTool` 只返回文本，不暴露 `Result.isError`；失败判定依据 `错误：` 前缀或明确的失败文本。')
  lines.push('')
  if (report.connection?.attempts?.length) {
    lines.push('## 连接尝试')
    lines.push('')
    lines.push('| 阶段 | ok | HTTP | 页面数 | 耗时 | 错误 |')
    lines.push('| --- | --- | ---: | ---: | ---: | --- |')
    for (const attempt of report.connection.attempts) {
      lines.push(
        `| ${mdCell(attempt.kind, 30)} | ${attempt.ok ? 'yes' : 'no'} | ${attempt.status ?? '—'} | ${attempt.pageCount ?? '—'} | ${fmtMs(attempt.ms)} | ${mdCell(attempt.error || '', 200)} |`
      )
    }
    lines.push('')
  }
  lines.push('---')
  lines.push(`JSON：\`${displayPath(report.artifacts.json)}\``)
  lines.push(`Markdown：\`${displayPath(report.artifacts.markdown)}\``)
  lines.push('')
  return `${lines.join('\n')}\n`
}

function writeReports(report, opts) {
  const jsonPath = opts.jsonPath || path.join(opts.outDir, 'latest.json')
  const mdPath = opts.mdPath || path.join(opts.outDir, 'latest.md')
  fs.mkdirSync(path.dirname(jsonPath), { recursive: true })
  fs.mkdirSync(path.dirname(mdPath), { recursive: true })
  report.artifacts = {
    json: displayPath(jsonPath),
    markdown: displayPath(mdPath),
  }
  fs.writeFileSync(jsonPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
  fs.writeFileSync(mdPath, renderMarkdown(report), 'utf8')
  return { jsonPath, mdPath }
}

function printCaseLine(item, index, total) {
  const label = statusLabel(item).padEnd(6)
  const timing = item.timing?.pageMs == null ? '' : `${fmtMs(item.timing.pageMs)}`
  let note = ''
  if (item.status === 'fail') note = item.reason
  else if (item.status === 'skip') note = item.reason
  else if (item.warnings?.length) note = item.warnings.map((warning) => warning.message).join('；')
  const suffix = note ? ` — ${firstLine(note, 180)}` : ''
  console.log(`[${index + 1}/${total}] ${label} ${item.id.padEnd(38)} ${timing}${suffix}`)
}

function printSummary(report, opts) {
  console.log('')
  console.log(`结论：${verdictLabel(report)}`)
  console.log(
    `用例：${report.summary.total} 总数 / ${report.summary.passed} 通过 / ${report.summary.failed} 失败 / ${report.summary.skipped} 跳过 / ${report.summary.warned} 警告`
  )
  if (report.error) console.error(`错误：${report.error.message}`)
  const failures = report.cases.filter((item) => item.status === 'fail' || item.status === 'error')
  for (const item of failures) console.error(`  ❌ ${item.id}: ${item.reason}`)
  const skips = report.cases.filter((item) => item.status === 'skip')
  for (const item of skips) console.log(`  ⏭ ${item.id}: ${item.reason}`)
  const warnings = report.cases.filter((item) => item.warnings?.length)
  for (const item of warnings) {
    for (const warning of item.warnings) console.log(`  ⚠ ${item.id}: ${warning.message}`)
  }
  if (report.summary.slowest) {
    console.log(
      `最慢：${report.summary.slowest.id} page ${fmtMs(report.summary.slowest.pageMs)} / wall ${fmtMs(report.summary.slowest.wallMs)}`
    )
  }
  console.log(`JSON：${report.artifacts?.json || '—'}`)
  console.log(`Markdown：${report.artifacts?.markdown || '—'}`)
  if (report.verdict === 'pass_with_skips' && !opts.strict) {
    console.log('提示：存在跳过/警告；如需 CI 严格模式，加 --strict。')
  }
}

function baseReport(opts, runId) {
  const startedAt = new Date().toISOString()
  return {
    schemaVersion: 1,
    harness: {
      name: 'dla-agent-tool-evals',
      version: HARNESS_VERSION,
      node: process.version,
      platform: process.platform,
    },
    runId,
    label: opts.label || '',
    command: `${process.execPath} ${process.argv.slice(1).join(' ')}`,
    startedAt,
    startedAtLocal: new Date(startedAt).toLocaleString('zh-CN', { hour12: false }),
    finishedAt: null,
    finishedAtLocal: null,
    durationMs: null,
    ok: false,
    verdict: 'running',
    error: null,
    args: {
      host: opts.host,
      port: opts.port,
      package: opts.package || '',
      serial: opts.serial || '',
      socket: opts.socket || '',
      noAdb: opts.noAdb,
      casesPath: displayPath(opts.casesPath),
      timeoutMs: opts.timeout,
      connectTimeoutMs: opts.connectTimeout,
      filter: opts.filter || '',
      strict: opts.strict,
    },
    connection: null,
    environment: null,
    summary: {
      total: 0,
      passed: 0,
      failed: 0,
      skipped: 0,
      warned: 0,
      errors: 0,
      byCategory: {},
      timingByTool: {},
      slowest: null,
    },
    cases: [],
    artifacts: { json: null, markdown: null },
  }
}

async function main() {
  let opts
  try {
    opts = parseArgs(process.argv.slice(2))
  } catch (error) {
    logError(error.message)
    console.error('')
    console.error(usage())
    return 2
  }
  if (opts.help) {
    console.log(usage())
    return 0
  }
  opts.runId = new Date().toISOString().replace(/[:.]/g, '-')
  let cases
  try {
    cases = loadCases(opts.casesPath)
    if (opts.filter) {
      const regex = compilePattern(opts.filter, '')
      cases = cases.filter((item) => regex.test(item.id))
      if (cases.length === 0) throw new Error(`--filter 没有匹配任何用例：${opts.filter}`)
    }
  } catch (error) {
    logError(error.message)
    return 2
  }
  if (opts.list) {
    console.log(`共 ${cases.length} 个用例：`)
    for (const item of cases) {
      console.log(`  ${item.id.padEnd(40)} ${item.kind.padEnd(10)} ${item.tool || 'AndroidHost.shellTest'}`)
    }
    return 0
  }

  const report = baseReport(opts, opts.runId)
  let exitCode = 0
  let client
  try {
    const connection = await openConnection(opts)
    client = connection.client
    report.connection = connection.details
    report.environment = connection.environment
    console.log(`CDP：${connection.details.baseUrl}`)
    console.log(`页面：${connection.details.target?.title || '（无标题）'}`)
    console.log(`URL：${connection.details.target?.url || '—'}`)
    if (connection.environment.shizuku) {
      const shizuku = connection.environment.shizuku
      console.log(`Shizuku：running=${shizuku.running} granted=${shizuku.granted} version=${shizuku.version || '—'}`)
    }
    if (connection.details.adb?.package) {
      const pkg = connection.details.adb.package
      console.log(
        `包：${pkg.name} version=${pkg.versionName || '—'} debuggable=${pkg.debuggable} pids=${(pkg.pids || []).join(',') || '—'}`
      )
    }
    console.log('')
    for (let index = 0; index < cases.length; index += 1) {
      const record = await runCase(client, cases[index], opts, connection.environment)
      report.cases.push(record)
      printCaseLine(record, index, cases.length)
    }
    finalizeReport(report, opts)
    exitCode =
      report.verdict === 'strict_fail' || report.verdict === 'fail'
        ? opts.strict && report.verdict === 'strict_fail'
          ? 2
          : 1
        : 0
  } catch (error) {
    report.ok = false
    report.verdict = 'infra_error'
    report.error = {
      name: error.name || 'Error',
      message: error.message || String(error),
      stack: opts.verbose ? error.stack : undefined,
    }
    if (error instanceof InfraError && error.details) report.connection = error.details
    finalizeReport(report, opts)
    report.ok = false
    report.verdict = 'infra_error'
    exitCode = 3
  } finally {
    if (client) {
      try {
        client.close()
      } catch {
        // ignore
      }
    }
  }

  try {
    writeReports(report, opts)
  } catch (error) {
    logError(`写报告失败：${error.message}`)
    return 4
  }
  printSummary(report, opts)
  return exitCode
}

main()
  .then((code) => {
    process.exitCode = code
  })
  .catch((error) => {
    logError(error?.stack || error?.message || String(error))
    process.exitCode = 1
  })
