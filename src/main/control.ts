import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { randomBytes } from 'node:crypto'
import { chmodSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { CONTROL_PORT } from '@shared/defaults'
import { log } from './logger'

export interface ControlHandlers {
  state(): unknown
  start(insert: boolean): Promise<unknown>
  stop(): Promise<unknown>
  toggle(): Promise<unknown>
  cancel(): Promise<unknown>
  language(language?: string, cycle?: boolean): Promise<unknown>
  show(page?: string): Promise<unknown>
  paste(text: string): Promise<unknown>
  devices(): Promise<unknown>
  setDevice(id: string): Promise<unknown>
  quit(): Promise<unknown>
}

export interface ControlInfo {
  port: number
  token: string
  pid: number
  startedAt: number
}

const MAX_BODY = 1024 * 1024

export class ControlServer {
  private server: Server | null = null
  private info: ControlInfo | null = null
  private heartbeat: NodeJS.Timeout | null = null
  private readonly token = randomBytes(32).toString('hex')
  private readonly file: string

  constructor(
    private readonly configDir: string,
    private readonly handlers: ControlHandlers
  ) {
    this.file = join(configDir, 'control.json')
  }

  get port(): number {
    return this.info?.port ?? 0
  }

  async start(): Promise<number> {
    for (let port = CONTROL_PORT; port < CONTROL_PORT + 10; port++) {
      try {
        await this.listen(port)
        this.info = { port, token: this.token, pid: process.pid, startedAt: Date.now() }
        this.persist()
        // Something (a cleanup script, a second instance's will-quit) can delete
        // the file while we keep running; put it back, but do not rewrite it
        // every tick.
        this.heartbeat = setInterval(() => {
          if (!existsSync(this.file)) this.persist()
        }, 3000)
        this.heartbeat.unref?.()
        log.info(`[control] listening on 127.0.0.1:${port}`)
        return port
      } catch (error) {
        const code = (error as NodeJS.ErrnoException).code
        if (code !== 'EADDRINUSE') throw error
      }
    }
    throw new Error('No free control port')
  }

  private persist(): void {
    if (!this.info) return
    try {
      mkdirSync(dirname(this.file), { recursive: true })
      writeFileSync(this.file, JSON.stringify(this.info, null, 2), { mode: 0o600 })
      chmodSync(this.file, 0o600)
    } catch (error) {
      log.warn('[control] could not write control file', String(error))
    }
  }

  private listen(port: number): Promise<void> {
    return new Promise((resolve, reject) => {
      const server = createServer((req, res) => {
        this.route(req, res).catch((error) => {
          log.warn('[control] request failed', String(error))
          this.send(res, 500, { ok: false, error: String(error) })
        })
      })
      server.once('error', reject)
      server.listen(port, '127.0.0.1', () => {
        server.removeListener('error', reject)
        this.server = server
        server.on('error', (error) => log.warn('[control] server error', String(error)))
        resolve()
      })
    })
  }

  private send(res: ServerResponse, status: number, body: unknown): void {
    const json = JSON.stringify(body)
    res.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Length': Buffer.byteLength(json)
    })
    res.end(json)
  }

  private async body(req: IncomingMessage): Promise<Record<string, unknown>> {
    const chunks: Buffer[] = []
    let size = 0
    for await (const chunk of req) {
      size += (chunk as Buffer).length
      if (size > MAX_BODY) throw new Error('body too large')
      chunks.push(chunk as Buffer)
    }
    if (chunks.length === 0) return {}
    try {
      const parsed = JSON.parse(Buffer.concat(chunks).toString('utf8')) as unknown
      return typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : {}
    } catch {
      return {}
    }
  }

  private async route(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1')
    const path = url.pathname.replace(/\/+$/, '') || '/'

    if (path === '/health' && req.method === 'GET') {
      this.send(res, 200, { ok: true, app: 'reverseuno', pid: process.pid })
      return
    }

    const auth = req.headers.authorization ?? ''
    if (auth !== `Bearer ${this.token}`) {
      this.send(res, 401, { ok: false, error: 'unauthorized' })
      return
    }

    if (path === '/state' && req.method === 'GET') {
      this.send(res, 200, { ok: true, state: this.handlers.state() })
      return
    }

    if (path === '/devices' && req.method === 'GET') {
      this.send(res, 200, { ok: true, devices: await this.handlers.devices() })
      return
    }

    if (req.method !== 'POST') {
      this.send(res, 405, { ok: false, error: 'method not allowed' })
      return
    }

    const body = await this.body(req)
    // One line per CLI call, so a log shows whether the key binds fired.
    log.info('[control] request', { path })

    switch (path) {
      case '/start':
        this.send(res, 200, { ok: true, result: await this.handlers.start(body.insert !== false) })
        return
      case '/stop':
        this.send(res, 200, { ok: true, result: await this.handlers.stop() })
        return
      case '/toggle':
        this.send(res, 200, { ok: true, result: await this.handlers.toggle() })
        return
      case '/cancel':
        this.send(res, 200, { ok: true, result: await this.handlers.cancel() })
        return
      case '/language':
        this.send(res, 200, {
          ok: true,
          result: await this.handlers.language(
            typeof body.language === 'string' ? body.language : undefined,
            body.cycle === true
          )
        })
        return
      case '/show':
        this.send(res, 200, {
          ok: true,
          result: await this.handlers.show(typeof body.page === 'string' ? body.page : undefined)
        })
        return
      case '/paste':
        if (typeof body.text !== 'string' || !body.text) {
          this.send(res, 400, { ok: false, error: 'text required' })
          return
        }
        this.send(res, 200, { ok: true, result: await this.handlers.paste(body.text) })
        return
      case '/device':
        if (typeof body.id !== 'string') {
          this.send(res, 400, { ok: false, error: 'id required' })
          return
        }
        this.send(res, 200, { ok: true, result: await this.handlers.setDevice(body.id) })
        return
      case '/quit':
        this.send(res, 200, { ok: true })
        setTimeout(() => void this.handlers.quit(), 50).unref?.()
        return
      default:
        this.send(res, 404, { ok: false, error: 'not found' })
    }
  }

  async stop(): Promise<void> {
    if (this.heartbeat) clearInterval(this.heartbeat)
    this.heartbeat = null
    if (this.server) {
      await new Promise<void>((resolve) => this.server?.close(() => resolve()))
      this.server = null
    }
    if (existsSync(this.file)) {
      try {
        rmSync(this.file)
      } catch {
        // ignore
      }
    }
    this.info = null
  }
}
