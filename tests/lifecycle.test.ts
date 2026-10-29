import { EventEmitter } from 'node:events'
import { describe, expect, it } from 'vitest'
import { ALREADY_RUNNING_EXIT, findUiBinary, openHub, PageReplay, Supervisor, type Child } from '../src/main/supervisor'

class FakeChild extends EventEmitter implements Child {
  killed = false
  kill(): boolean {
    this.killed = true
    return true
  }
}

function harness() {
  const children: FakeChild[] = []
  const spawns: string[][] = []
  const timers: (() => void)[] = []
  let now = 0
  const logs: string[] = []
  const sup = new Supervisor({
    spawn: (args) => {
      spawns.push(args)
      const child = new FakeChild()
      children.push(child)
      return child
    },
    now: () => now,
    setTimer: (fn) => {
      timers.push(fn)
      return () => timers.splice(timers.indexOf(fn), 1)
    },
    log: (message) => logs.push(message)
  })
  const exitLast = () => children.at(-1)!.emit('exit', 1, null)
  const runTimers = () => timers.splice(0).forEach((fn) => fn())
  return { sup, children, spawns, timers, logs, exitLast, runTimers, advance: (ms: number) => (now += ms) }
}

describe('Qt app supervisor', () => {
  it('supervisor restarts the child after an exit', () => {
    const h = harness()
    expect(h.sup.ensureRunning(['--page', 'settings'])).toBe(true)
    h.exitLast()
    expect(h.sup.running).toBe(false)
    expect(h.spawns).toHaveLength(1)
    h.runTimers()
    expect(h.spawns).toHaveLength(2)
    // a crash restart comes up hidden: no --page, so it never steals focus mid-dictation
    expect(h.spawns[1]).toEqual([])
    expect(h.sup.running).toBe(true)
  })

  it('supervisor stops restarting after 5 exits within a minute', () => {
    const h = harness()
    h.sup.ensureRunning()
    for (let i = 0; i < 5; i++) {
      h.advance(1000)
      h.exitLast()
      h.runTimers()
    }
    expect(h.spawns).toHaveLength(6)
    h.advance(1000)
    h.exitLast()
    h.runTimers()
    expect(h.spawns).toHaveLength(6)
    expect(h.logs.filter((line) => line.includes('giving up'))).toHaveLength(1)
    // a user-initiated start still works after the cap tripped
    expect(h.sup.ensureRunning()).toBe(true)
    expect(h.spawns).toHaveLength(7)
  })

  it('exits spread over more than a minute keep restarting', () => {
    const h = harness()
    h.sup.ensureRunning()
    for (let i = 0; i < 8; i++) {
      h.advance(20_000)
      h.exitLast()
      h.runTimers()
    }
    expect(h.spawns).toHaveLength(9)
  })

  it('ensureRunning starts it if not running and is idempotent', () => {
    const h = harness()
    expect(h.sup.ensureRunning()).toBe(true)
    expect(h.sup.ensureRunning()).toBe(false)
    expect(h.spawns).toHaveLength(1)
    // during a pending restart: start now, with the page, and drop the timer
    h.exitLast()
    expect(h.sup.ensureRunning(['--page', 'history'])).toBe(true)
    expect(h.spawns).toEqual([[], ['--page', 'history']])
    expect(h.timers).toHaveLength(0)
  })

  it('an error and an exit from one failed spawn count once', () => {
    const h = harness()
    h.sup.ensureRunning()
    h.children[0]!.emit('error', new Error('ENOENT'))
    h.children[0]!.emit('exit', null, null)
    h.runTimers()
    expect(h.spawns).toHaveLength(2)
  })

  it('quit kills the child and does not restart', () => {
    const h = harness()
    h.sup.ensureRunning()
    h.sup.stop()
    expect(h.children[0]!.killed).toBe(true)
    h.exitLast()
    h.runTimers()
    expect(h.spawns).toHaveLength(1)
    expect(h.sup.ensureRunning()).toBe(false)
  })

  it('a second Qt app that finds one running is not restarted and costs no budget', () => {
    const h = harness()
    for (let i = 0; i < 10; i++) {
      h.sup.ensureRunning()
      h.children.at(-1)!.emit('exit', ALREADY_RUNNING_EXIT, null)
      h.runTimers()
    }
    expect(h.spawns).toHaveLength(10)
    expect(h.sup.running).toBe(false)
    expect(h.timers).toHaveLength(0)
    expect(h.logs.filter((line) => line.includes('already running'))).toHaveLength(10)
    expect(h.logs.some((line) => line.includes('giving up'))).toBe(false)
    // the budget is untouched: a real crash loop still gets all 5 restarts
    h.sup.ensureRunning()
    for (let i = 0; i < 5; i++) {
      h.exitLast()
      h.runTimers()
    }
    expect(h.spawns).toHaveLength(16)
  })

  it('no binary: never spawns', () => {
    const sup = new Supervisor({ spawn: null, log: () => undefined })
    expect(sup.ensureRunning()).toBe(false)
    expect(sup.running).toBe(false)
  })
})

describe('show', () => {
  it('show starts the child if not running', () => {
    const h = harness()
    const events: unknown[] = []
    const show = openHub(h.sup, (event) => events.push(event) > 0, new PageReplay())
    show('settings')
    expect(h.spawns).toEqual([['--page', 'settings']])
    expect(events).toEqual([{ type: 'page', page: 'settings' }])
    // already running: the page event reaches it over the stream, no second process
    show()
    expect(h.spawns).toHaveLength(1)
    expect(events.at(-1)).toEqual({ type: 'page', page: 'history' })
  })
})

describe('findUiBinary', () => {
  const exists = (set: string[]) => (path: string) => set.includes(path)
  const base = { packaged: false, execDir: '/opt/reverseuno', appPath: '/repo', path: '/usr/bin:/bin' }

  it('REVERSEUNO_NO_UI=1 disables it', () => {
    expect(findUiBinary({ ...base, env: { REVERSEUNO_NO_UI: '1', REVERSEUNO_UI: '/x' }, exists: () => true })).toBeNull()
  })

  it('prefers REVERSEUNO_UI, then next to the package, then the app dir, then PATH', () => {
    const all = ['/x/ui', '/opt/reverseuno/reverseuno-ui', '/repo/qt/reverseuno-ui', '/bin/reverseuno-ui']
    expect(findUiBinary({ ...base, env: { REVERSEUNO_UI: '/x/ui' }, exists: exists(all) })).toBe('/x/ui')
    expect(findUiBinary({ ...base, packaged: true, env: {}, exists: exists(all) })).toBe(
      '/opt/reverseuno/reverseuno-ui'
    )
    // An installed package on PATH does not shadow the checkout's own build.
    expect(findUiBinary({ ...base, env: {}, exists: exists(all.slice(2)) })).toBe('/repo/qt/reverseuno-ui')
    expect(findUiBinary({ ...base, env: {}, exists: exists(all.slice(3)) })).toBe('/bin/reverseuno-ui')
    expect(findUiBinary({ ...base, env: {}, exists: () => false })).toBeNull()
  })
})
