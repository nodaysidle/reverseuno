import { appendFileSync, existsSync, mkdirSync, renameSync, statSync } from 'node:fs'
import { join } from 'node:path'

let logFile = ''
let verbose = process.env.REVERSEUNO_DEBUG === '1'

export function initLogger(directory: string): string {
  mkdirSync(directory, { recursive: true })
  logFile = join(directory, 'app.log')
  if (existsSync(logFile) && statSync(logFile).size > 2 * 1024 * 1024) {
    try {
      renameSync(logFile, join(directory, 'app.1.log'))
    } catch {
      // Rotation is best-effort; keep logging to the same file.
    }
  }
  return logFile
}

export function getLogPath(): string {
  return logFile
}

function redact(value: unknown): string {
  let text = typeof value === 'string' ? value : JSON.stringify(value)
  text = text.replace(/(Bearer|Token|Api-Key)\s+[A-Za-z0-9._\-+/=]{8,}/gi, '$1 [redacted]')
  text = text.replace(/[A-Za-z0-9_-]{24,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}/g, '[redacted-jwt]')
  text = text.replace(/(sk-[A-Za-z0-9_-]{8,})/g, '[redacted-key]')
  return text
}

function write(level: string, args: unknown[]): void {
  const line = `${new Date().toISOString()} ${level} ${args.map((arg) => redact(arg)).join(' ')}`
  if (verbose || level === 'ERROR' || level === 'WARN') {
    // eslint-disable-next-line no-console
    console[level === 'ERROR' ? 'error' : level === 'WARN' ? 'warn' : 'log'](line)
  }
  if (!logFile) return
  try {
    appendFileSync(logFile, `${line}\n`, { mode: 0o600 })
  } catch {
    // Never let logging take the app down.
  }
}

export const log = {
  info: (...args: unknown[]) => write('INFO', args),
  warn: (...args: unknown[]) => write('WARN', args),
  error: (...args: unknown[]) => write('ERROR', args),
  debug: (...args: unknown[]) => {
    if (verbose) write('DEBUG', args)
  }
}
