import { execFileSync } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

const SCRIPT = resolve(__dirname, '../scripts/install-hyprland.py')

// Runs the installer against a throwaway HOME. A stub hyprctl first on PATH keeps
// `hyprctl reload` from ever reaching the live compositor.
describe('install-hyprland.py', () => {
  let home: string
  let bindings: string
  let env: NodeJS.ProcessEnv

  beforeEach(() => {
    home = mkdtempSync(join(tmpdir(), 'reverseuno-hypr-'))
    mkdirSync(join(home, '.config/hypr'), { recursive: true })
    mkdirSync(join(home, '.local/bin'), { recursive: true })
    writeFileSync(join(home, '.local/bin/reverseuno'), '')
    const stubs = join(home, 'stubs')
    mkdirSync(stubs)
    writeFileSync(join(stubs, 'hyprctl'), '#!/bin/sh\necho stub\n')
    chmodSync(join(stubs, 'hyprctl'), 0o755)
    bindings = join(home, '.config/hypr/bindings.lua')
    env = { ...process.env, HOME: home, PATH: `${stubs}:${process.env.PATH}` }
  })

  afterEach(() => rmSync(home, { recursive: true, force: true }))

  const run = () => execFileSync('python3', [SCRIPT], { env, encoding: 'utf8' })
  const cli = () => join(home, '.local/bin/reverseuno')

  it('upgrades the old Electron rules to the Qt Hub and Overlay rules, then is a no-op', () => {
    writeFileSync(
      bindings,
      [
        'o.bind("SUPER + Return", "Terminal", o.launch_terminal())',
        '-- >>> reverseuno (managed by scripts/install-hyprland.py)',
        'o.bind("Super_R", "reverseuno hold", "/old/reverseuno start")',
        'o.window({ class = "^reverseuno$", title = "^reverseuno-overlay$" }, {',
        '  float = true,',
        '})',
        'o.window({ class = "^reverseuno$", title = "^reverseuno$" }, {',
        '  size = { 1180, 820 },',
        '})',
        '-- <<< reverseuno',
        'o.bind("SUPER + B", "Browser", o.launch("firefox"))',
        ''
      ].join('\n')
    )
    expect(run()).toContain('wrote reverseuno block')
    const text = readFileSync(bindings, 'utf8')

    expect(text).toContain(`o.bind("Super_R", "reverseuno hold", "${cli()} start")`)
    expect(text).toContain(`o.bind("Super_R", "reverseuno release", "${cli()} stop", { release = true })`)
    expect(text).toContain(`o.bind("SUPER + Super_R", "reverseuno release (super held)", "${cli()} stop", { release = true })`)
    expect(text).toContain('size = { 980, 660 }')
    expect(text).toContain('hl.layer_rule({ match = { namespace = "^reverseuno-overlay$" }')
    expect(text).not.toContain('reverseuno-overlay$" }, {') // the old window rule
    expect(text).not.toContain('1180')
    expect(text).not.toContain('/old/reverseuno')
    expect(text.match(/>>> reverseuno/g)).toHaveLength(1)
    // Lines around the block survive, in place.
    expect(text.indexOf('Terminal')).toBeLessThan(text.indexOf('>>> reverseuno'))
    expect(text.indexOf('Browser')).toBeGreaterThan(text.indexOf('<<< reverseuno'))

    expect(run()).toContain('already up to date')
    expect(readFileSync(bindings, 'utf8')).toBe(text)
    expect(readdirSync(join(home, '.config/hypr')).filter((f) => f.includes('.bak.'))).toHaveLength(1)
  })

  it('replaces the unmarked legacy block and disables a WhisperBar Right Super bind', () => {
    writeFileSync(
      bindings,
      [
        'o.bind("Super_R", "dictate", "whisper-bar toggle")',
        '-- reverseuno: hold Right Cmd to dictate',
        'o.bind("Super_R", "reverseuno hold", "x start")',
        'o.window({ class = "^reverseuno$", title = "^reverseuno$" }, {',
        '  float = true,',
        '})',
        ''
      ].join('\n')
    )
    run()
    const text = readFileSync(bindings, 'utf8')
    expect(text).not.toContain('hold Right Cmd')
    expect(text).toContain('-- (disabled by reverseuno install) o.bind("Super_R", "dictate", "whisper-bar toggle")')
    expect(text).toContain(`"${cli()} start"`)
    expect(text.match(/class = "\^reverseuno\$"/g)).toHaveLength(1)
  })
})
