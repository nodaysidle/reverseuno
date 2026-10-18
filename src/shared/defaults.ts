import type { AppRule, Settings } from './types'

export const LANGUAGE_OPTIONS = [
  { value: 'auto', label: 'Auto-detect' },
  { value: 'multi', label: 'Multilingual' },
  { value: 'en', label: 'English' },
  { value: 'sl', label: 'Slovenian' },
  { value: 'it', label: 'Italian' },
  { value: 'de', label: 'German' },
  { value: 'es', label: 'Spanish' },
  { value: 'fr', label: 'French' },
  { value: 'pt', label: 'Portuguese' },
  { value: 'nl', label: 'Dutch' },
  { value: 'ja', label: 'Japanese' },
  { value: 'ko', label: 'Korean' },
  { value: 'zh', label: 'Chinese' }
] as const

export const DEEPGRAM_MODELS = ['nova-3', 'nova-2', 'enhanced', 'base'] as const

export const FORMAT_MODELS = [
  'google/gemini-2.5-flash',
  'openai/gpt-4.1-mini',
  'openai/gpt-4o-mini',
  'deepseek/deepseek-chat',
  'anthropic/claude-3.5-haiku',
  'meta-llama/llama-3.3-70b-instruct'
] as const

export const DEFAULT_APP_RULES: AppRule[] = [
  {
    pattern: 'ghostty|alacritty|kitty|foot|wezterm|org\\.wezfurlong\\.wezterm|xterm|konsole',
    keys: 'shift-insert'
  },
  {
    pattern: '\\b(code|cursor|windsurf|zed|jetbrains|idea|pycharm|webstorm|goland|clion)\\b',
    keys: 'shift-insert'
  },
  {
    pattern: 'remmina|virt-manager|anydesk|teamviewer|rustdesk|wfica32|mstsc|rdcman',
    keys: 'ctrl-v',
    restoreDelayMs: 5000
  },
  {
    pattern: 'libreoffice|onlyoffice|thunderbird|whatsapp',
    keys: 'ctrl-v',
    restoreDelayMs: 3000
  }
]

export const DEFAULT_SETTINGS: Settings = {
  stt: {
    provider: 'deepgram',
    model: 'nova-3',
    language: 'auto',
    smartFormat: true,
    punctuate: true,
    keytermLimit: 50,
    endpointingMs: 300,
    utteranceEndMs: 1000
  },
  format: {
    enabled: true,
    provider: 'openrouter',
    model: 'google/gemini-2.5-flash',
    baseUrl: 'https://openrouter.ai/api/v1',
    cleanup: 'light',
    style: 'casual',
    customInstructions: '',
    timeoutMs: 4500,
    includeAppContext: true
  },
  insert: {
    mode: 'paste',
    defaultKeys: 'ctrl-v',
    appRules: DEFAULT_APP_RULES,
    keyDelayMs: 30,
    applyDelayMs: 80,
    waitForModifiersMs: 180,
    restoreDelayMs: 500,
    copyToPrimary: false,
    appendSpace: true
  },
  audio: {
    inputDevice: '',
    sampleRate: 16000,
    vadThreshold: 0.0015,
    silenceDrop: true
  },
  overlay: {
    showAtRest: false,
    position: 'bottom',
    offsetY: 18,
    sounds: false,
    hideAfterMs: 1400,
    errorHideAfterMs: 4200
  },
  hotkey: {
    display: 'Right Cmd (Super_R)',
    hold: true
  },
  history: {
    maxEntries: 500,
    autoLearn: true
  },
  system: {
    launchAtLogin: false,
    openHubOnStart: true
  },
  ui: {
    theme: 'cream',
    reduceMotion: false
  }
}

export const APP_NAME = 'reverseuno'
export const APP_ID = 'dev.nodaysidle.reverseuno'
export const CONTROL_PORT = 47100
export const SUPPORTED_AUDIO_RATES = [8000, 16000, 22050, 24000, 32000, 44100, 48000]
