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

// Fallbacks for when OpenRouter's model list cannot be fetched. Settings loads
// the live list and accepts any model id.
export const FORMAT_MODELS = [
  'google/gemini-2.5-flash',
  'google/gemini-3.8-flash',
  'google/gemini-3.5-flash-lite',
  'anthropic/claude-haiku-5.5',
  'deepseek/deepseek-v4.1-flash',
  'openai/gpt-4.1-mini'
] as const

export const OPENROUTER_STT_MODELS = [
  'openai/gpt-4o-mini-transcribe',
  'openai/gpt-4o-transcribe',
  'openai/whisper-large-v3-turbo',
  'openai/whisper-large-v3',
  'elevenlabs/scribe-v2',
  'mistralai/voxtral-mini-transcribe',
  'deepgram/nova-3'
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
    openrouterModel: 'openai/gpt-4o-mini-transcribe',
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
    timeoutMs: 2500,
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
  history: {
    maxEntries: 500,
    autoLearn: true
  },
  system: {
    launchAtLogin: false,
    openHubOnStart: true
  },
  appearance: {
    theme: 'omarchy'
  }
}

export const APP_NAME = 'reverseuno'
export const APP_ID = 'dev.nodaysidle.reverseuno'
export const CONTROL_PORT = 47100
export const SUPPORTED_AUDIO_RATES = [8000, 16000, 22050, 24000, 32000, 44100, 48000]
