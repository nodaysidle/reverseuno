export function formatClock(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

export function truncate(text: string, max: number): string {
  if (text.length <= max) return text
  return `${text.slice(0, max - 1).trimEnd()}…`
}

const APP_NAMES: Record<string, string> = {
  'com.mitchellh.ghostty': 'Ghostty',
  ghostty: 'Ghostty',
  alacritty: 'Alacritty',
  kitty: 'kitty',
  foot: 'foot',
  code: 'VS Code',
  cursor: 'Cursor',
  windsurf: 'Windsurf',
  zed: 'Zed',
  'brave-origin': 'Brave',
  'brave-browser': 'Brave',
  firefox: 'Firefox',
  'org.mozilla.firefox': 'Firefox',
  chromium: 'Chromium',
  'google-chrome': 'Chrome',
  slack: 'Slack',
  discord: 'Discord',
  'com.discordapp.discord': 'Discord',
  telegram: 'Telegram',
  'org.telegram.desktop': 'Telegram',
  notion: 'Notion',
  obsidian: 'Obsidian',
  thunderbird: 'Thunderbird',
  'org.gnome.nautilus': 'Files',
  'org.gnome.texteditor': 'Text Editor',
  'org.gnome.console': 'Console',
  'org.kde.konsole': 'Konsole',
  remmina: 'Remmina',
  'org.remmina.remmina': 'Remmina'
}

export function friendlyApp(value: string): string {
  if (!value) return ''
  const key = value.toLowerCase()
  const mapped = APP_NAMES[key]
  if (mapped) return mapped
  const last = value.split('.').pop() || value
  return last
    .replace(/[-_]+/g, ' ')
    .replace(/\b\w/g, (letter) => letter.toUpperCase())
    .slice(0, 18)
}
