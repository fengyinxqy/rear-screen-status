/**
 * DSH -> rear screen status bridge.
 *
 * Publishes what this DSH session is doing to the status daemon the Xiaomi rear-screen panel
 * polls, so the back of the phone answers "is the agent working?" without a Codex hook in the
 * middle.
 *
 * Install: copy this file to ~/.dsh/rear-status/dsh-plugin.mjs and register it in
 * ~/.dsh/cordis.patch.yml (docs/dsh-plugin.md has the exact snippet):
 *
 *   - insert:
 *       - id: rear-status
 *         name: "file:///<absolute path to dsh-plugin.mjs>"
 *
 * Events, as DSH names them:
 *   agent/session-start  -> developing
 *   agent/pre-step       -> developing (a turn is running)
 *   agent/turn-stopping  -> idle: the turn is over, nothing is running any more
 *   agent/status idle|stopped -> idle
 *   agent/disposed       -> idle
 *
 * There is deliberately no "waiting for you" state. It was mapped from turn-stopping, and that
 * is wrong: a turn ending means the work is done, not that the agent is asking anything. The
 * two cannot be told apart from lifecycle events — a session that finished and a session
 * blocked on a question look identical — so the panel is told only what is provable: working
 * or not working. An idle screen is a better answer than a wrong one.
 *
 * Delivery is an HTTP POST to the existing statusd endpoint; if that is unreachable the same
 * payload is written to a file the phone can read, so the panel degrades rather than goes dark.
 * Nothing here throws: a status bridge must never break the session it reports on.
 */

import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join } from 'node:path'

export const name = 'rear-status'
export const inject = []

const DEFAULT_ENDPOINT = 'http://127.0.0.1:8770'
const STATE_FILE = join(homedir(), '.rear-status', 'dsh-state.json')
const LOG_FILE = join(homedir(), '.rear-status', 'dsh-plugin.log')
const CONFIG_FILE = join(homedir(), '.dsh', 'rear-status', 'config.json')

// One entry per live session, so several DSH windows do not overwrite each other's status.
const sessions = new Map()

function log(line) {
  try {
    mkdirSync(dirname(LOG_FILE), { recursive: true })
    appendFileSync(LOG_FILE, new Date().toISOString() + ' ' + line + '\n', { mode: 0o600 })
  } catch {
    // Logging is a convenience; never let it take the plugin down.
  }
}

function target() {
  let cfg = null
  try {
    cfg = JSON.parse(readFileSync(CONFIG_FILE, 'utf8'))
  } catch {
    // No config file is the normal case.
  }
  // An explicit environment override wins over the file: that is the conventional precedence,
  // and it is what makes the plugin testable against a throwaway endpoint instead of the live
  // daemon.
  const endpoint = process.env.REAR_STATUS_URL
    || (cfg && typeof cfg.endpoint === 'string' && cfg.endpoint ? cfg.endpoint : '')
    || DEFAULT_ENDPOINT
  const token = cfg && typeof cfg.token === 'string' ? cfg.token : null
  return { endpoint, token }
}

/** A session that has not moved for this long is gone. Closing a window or killing the
 *  process does not reliably fire agent/disposed, and a corpse left in the map keeps being
 *  counted as a live session for the rest of the day. */
const SESSION_TTL_MS = 6 * 60 * 60 * 1000

/** How long a 'developing' mark stays believable without a fresh beat. */
const LEASE_MS = 5 * 60 * 1000

/** The only two states this bridge publishes. See the header for why there is no third. */
const DEVELOPING = 'developing'
const IDLE = 'idle'

function prune() {
  const cutoff = Date.now() - SESSION_TTL_MS
  for (const key of [...sessions.keys()]) {
    const s = sessions.get(key)
    if (!s || s.at < cutoff) {
      sessions.delete(key)
      log('pruned stale session ' + String(key).slice(0, 8))
    }
  }
}

/** Compose the document the panel understands: how many sessions run, and the headline. */
function snapshot() {
  prune()
  // A 'developing' mark is a lease, not a fact: pre-step refreshes it, so a session that
  // stops beating has finished its turn even if the stop event never arrived. The window is
  // generous because a single long tool call can legitimately run for minutes.
  const leaseCutoff = Date.now() - LEASE_MS
  for (const sess of sessions.values()) {
    if (sess.status === DEVELOPING && sess.at < leaseCutoff) sess.status = IDLE
  }

  const live = [...sessions.values()]
  const working = live.filter((s) => s.status === DEVELOPING)
  const status = working.length > 0 ? DEVELOPING : IDLE

  // Name a session that is actually running; falling back to a finished one would caption
  // 开发中 with a window that is sitting at its prompt.
  const newest = working.slice().sort((a, b) => b.at - a.at)[0]

  return {
    status,
    // Sessions known but finished are not "active": the panel's count means "running now".
    active: working.length,
    // Which program this is, so the panel can show its mark rather than guess.
    agent: 'dsh',
    // Kept in the document so older phones and other readers still parse it. Always 0.
    waiting: 0,
    detail: newest ? newest.detail : '',
    updated: new Date().toISOString(),
  }
}

async function publish(reason) {
  const payload = snapshot()

  try {
    mkdirSync(dirname(STATE_FILE), { recursive: true })
    writeFileSync(STATE_FILE, JSON.stringify(payload, null, 2) + '\n', { mode: 0o600 })
  } catch (e) {
    log('state file failed: ' + e)
  }

  const t = target()
  const headers = { 'content-type': 'application/json' }
  if (t.token) headers.authorization = 'Bearer ' + t.token

  try {
    const res = await fetch(t.endpoint.replace(/\/$/, '') + '/push', {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(1200),
    })
    log(reason + ' -> ' + res.status + ' ' + JSON.stringify(payload))
  } catch (e) {
    // Expected whenever the daemon is not running; the file above is the fallback.
    log(reason + ' -> offline (' + (e && e.name ? e.name : e) + ') ' + JSON.stringify(payload))
  }
}

/** A short, human name for a session: the workspace folder, else the session id. */
function labelOf(agent) {
  try {
    const cwd = agent && agent.session && agent.session.header && agent.session.header.cwd
    if (typeof cwd === 'string' && cwd) {
      const parts = cwd.split(/[\\/]/).filter(Boolean)
      return parts[parts.length - 1] || cwd
    }
  } catch { }
  try {
    const id = agent && agent.session && agent.session.header && agent.session.header.id
    if (typeof id === 'string') return id.slice(0, 8)
  } catch { }
  return ''
}

function keyOf(agent) {
  try {
    const id = agent && agent.session && agent.session.header && agent.session.header.id
    if (typeof id === 'string' && id) return id
  } catch { }
  return 'unknown-' + Math.random().toString(36).slice(2, 8)
}

function mark(agent, status) {
  const key = keyOf(agent)
  sessions.set(key, { status, detail: labelOf(agent), at: Date.now() })
  return publish('agent/' + status + ' ' + key.slice(0, 8))
}

// A turn can run for many minutes, and the daemon expires a push it has not heard from.
// pre-step fires on every model step, which is a natural heartbeat — but POSTing on each one
// would be dozens of requests a minute for nothing: the panel polls on a timer anyway.
const HEARTBEAT_MS = 20_000
let lastPublishAt = 0
let lastSignature = ''

function beat(agent) {
  const key = keyOf(agent)
  const previous = sessions.get(key)
  const detail = labelOf(agent)
  const wasIdle = !previous || previous.status === IDLE

  sessions.set(key, { status: DEVELOPING, detail, at: Date.now() })

  const now = snapshot()
  const signature = now.status + '|' + now.active
  const stale = Date.now() - lastPublishAt > HEARTBEAT_MS

  // Publish when the headline changed (notably: resuming turns an idle panel back to 开发中),
  // or when the daemon has been quiet long enough that its TTL could lapse mid-turn.
  if (wasIdle || stale || signature !== lastSignature) {
    lastPublishAt = Date.now()
    lastSignature = signature
    return publish('agent/beat ' + key.slice(0, 8))
  }
  return undefined
}

/**
 * Keeps a non-idle state alive.
 *
 * The daemon expires a push it has not heard from, and after that its own session scan answers
 * instead — which is a different program's view of the world. A turn that is still running has
 * to be re-sent during its quiet stretches, not just when an event fires.
 *
 * Five minutes is deliberately slow: the panel already polls, this only stops the daemon from
 * forgetting. Idle publishes nothing, so a machine with no work stays silent.
 */
const KEEPALIVE_MS = 5 * 60 * 1000

function startKeepalive() {
  const timer = setInterval(() => {
    try {
      const now = snapshot()
      if (now.status === 'idle') return
      publish('keepalive')
    } catch { }
  }, KEEPALIVE_MS)
  // Never hold the process open on its own account.
  if (typeof timer.unref === 'function') timer.unref()
  return timer
}

export function apply(ctx) {
  log('plugin loaded')
  try { startKeepalive() } catch { }

  ctx.on('agent/session-start', ({ agent }) => {
    mark(agent, DEVELOPING)
  })

  // Fires before every model step, including each tool round trip: proof the turn is running.
  // It must always call next(): this is a chain step, not an observer.
  ctx.on('agent/pre-step', (payload, next) => {
    // Beat, not just a local write. Writing the map alone left the PUBLISHED state stuck:
    // when a turn resumed, the panel never went back to "working", because nothing was ever
    // sent — it sat on the old word until some other event happened.
    if (payload && payload.agent) {
      try { beat(payload.agent) } catch { }
    }
    return typeof next === 'function' ? next() : undefined
  })

  // The runtime's own status transition. This is the reliable half: it fires whenever the agent
  // changes state, including when a turn is cancelled from the UI — the case where
  // agent/turn-stopping never arrived and the panel stayed on 开发中.
  ctx.on('agent/status', (payload) => {
    try {
      const agent = payload && payload.agent
      const raw = payload && payload.status
      if (!agent) return
      const settled = raw === 'idle' || raw === 'stopped'
      log('agent/status ' + keyOf(agent).slice(0, 8) + ' -> ' + String(raw)
        + (settled ? ' (settled)' : ' (busy)'))
      mark(agent, settled ? IDLE : DEVELOPING)
    } catch (e) {
      log('agent/status failed: ' + e)
    }
  })

  // The turn is over. That is all this event proves: whether the agent finished a task or
  // stopped to ask something is not knowable here, so the panel is told "nothing running".
  ctx.on('agent/turn-stopping', ({ agent }) => {
    mark(agent, IDLE)
  })

  ctx.on('agent/disposed', ({ agent }) => {
    sessions.delete(keyOf(agent))
    publish('agent/disposed')
  })
}

export default { name, inject, apply }
