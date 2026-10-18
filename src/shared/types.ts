export type CleanupLevel = 'none' | 'light' | 'medium' | 'high'
export type WritingStyle = 'formal' | 'casual' | 'very-casual' | 'excited' | 'genz'
export type PasteKeys = 'ctrl-v' | 'shift-insert'
export type DictationState =
  | 'idle'
  | 'listening'
  | 'transcribing'
  | 'formatting'
  | 'inserting'
  | 'done'
  | 'error'
  | 'cancelled'

export interface AppRule {
  pattern: string
  keys: PasteKeys
  restoreDelayMs?: number
}

export interface Settings {
  stt: {
    provider: 'deepgram'
    model: string
    language: string
    smartFormat: boolean
    punctuate: boolean
    keytermLimit: number
    endpointingMs: number
    utteranceEndMs: number
  }
  format: {
    enabled: boolean
    provider: 'openrouter' | 'deepseek' | 'openai-compatible'
    model: string
    baseUrl: string
    cleanup: CleanupLevel
    style: WritingStyle
    customInstructions: string
    timeoutMs: number
    includeAppContext: boolean
  }
  insert: {
    mode: 'paste' | 'type'
    defaultKeys: PasteKeys
    appRules: AppRule[]
    keyDelayMs: number
    applyDelayMs: number
    waitForModifiersMs: number
    restoreDelayMs: number
    copyToPrimary: boolean
    appendSpace: boolean
  }
  audio: {
    inputDevice: string
    sampleRate: number
    vadThreshold: number
    silenceDrop: boolean
  }
  overlay: {
    showAtRest: boolean
    position: 'bottom' | 'top'
    offsetY: number
    sounds: boolean
    hideAfterMs: number
    errorHideAfterMs: number
  }
  hotkey: {
    display: string
    hold: boolean
  }
  history: {
    maxEntries: number
    autoLearn: boolean
  }
  system: {
    launchAtLogin: boolean
    openHubOnStart: boolean
  }
  ui: {
    theme: 'cream'
    reduceMotion: boolean
  }
}

export interface DictationEntry {
  id: string
  text: string
  rawText: string
  createdAt: number
  provider: string
  model: string
  language: string
  durationMs: number
  asrMs: number
  formatMs: number
  totalMs: number
  appClass: string
  appTitle: string
  cleanup: CleanupLevel
  style: WritingStyle
  inserted: boolean
  error?: string
  wordCount: number
  editedText?: string
  editDistance?: number
  flagged?: boolean
}

export interface DictionaryEntry {
  id: string
  word: string
  correction?: string
  starred: boolean
  uses: number
  createdAt: number
}

export interface Snippet {
  id: string
  trigger: string
  content: string
  uses: number
  createdAt: number
}

export interface Suggestion {
  id: string
  from: string
  to: string
  createdAt: number
  source: 'edit' | 'manual'
}

export interface Stats {
  totalWords: number
  totalDictations: number
  totalDurationMs: number
  totalSpeakingMs: number
  days: Record<string, number>
  apps: Record<string, number>
  corrections: number
  refined: number
}

export interface StoreData {
  version: number
  createdAt: number
  settings: Settings
  history: DictationEntry[]
  dictionary: DictionaryEntry[]
  snippets: Snippet[]
  suggestions: Suggestion[]
}

export type SecretName = 'deepgram' | 'openrouter' | 'deepseek' | 'xai' | 'typesafe'

export interface SecretStatus {
  name: SecretName
  present: boolean
  source: 'app' | 'environment' | 'whisperbar' | 'none'
  hint: string
}

export interface AudioDevice {
  id: string
  label: string
  isDefault: boolean
}

export interface StateEvent {
  state: DictationState
  message?: string
  interim?: string
  text?: string
  rawText?: string
  appClass?: string
  appTitle?: string
  startedAt?: number
  elapsedMs?: number
  error?: string
  entryId?: string
  language?: string
  words?: number
}

export interface Bootstrap {
  settings: Settings
  history: DictationEntry[]
  dictionary: DictionaryEntry[]
  snippets: Snippet[]
  suggestions: Suggestion[]
  stats: Stats
  secrets: SecretStatus[]
  version: string
  platform: string
  control: { port: number; running: boolean }
  paths: { config: string; history: string; log: string }
}

export interface ProviderTestResult {
  ok: boolean
  message: string
  latencyMs?: number
}

export interface ReverseunoApi {
  bootstrap(): Promise<Bootstrap>
  onState(callback: (event: StateEvent) => void): () => void
  onRefresh(callback: () => void): () => void
  onPage(callback: (page: string) => void): () => void
  onLevel(callback: (level: number) => void): () => void
  state(): Promise<StateEvent>
  updateSettings(patch: DeepPartial<Settings>): Promise<Settings>
  setSecret(name: SecretName, value: string): Promise<SecretStatus[]>
  clearSecret(name: SecretName): Promise<SecretStatus[]>
  testProvider(name: 'deepgram' | 'openrouter'): Promise<ProviderTestResult>
  listDevices(): Promise<AudioDevice[]>
  historyUpdate(id: string, text: string): Promise<{ entry: DictationEntry; suggestions: Suggestion[] }>
  historyDelete(id: string): Promise<void>
  historyClear(): Promise<void>
  historyCopy(id: string): Promise<void>
  historyInsert(id: string): Promise<void>
  historyFlag(id: string, flagged: boolean): Promise<void>
  dictionaryAdd(word: string, correction?: string): Promise<DictionaryEntry>
  dictionaryUpdate(id: string, patch: Partial<DictionaryEntry>): Promise<DictionaryEntry>
  dictionaryDelete(id: string): Promise<void>
  snippetAdd(trigger: string, content: string): Promise<Snippet>
  snippetUpdate(id: string, patch: Partial<Snippet>): Promise<Snippet>
  snippetDelete(id: string): Promise<void>
  suggestionAccept(id: string): Promise<void>
  suggestionDismiss(id: string): Promise<void>
  dictationStart(): Promise<void>
  dictationStop(): Promise<void>
  dictationToggle(): Promise<void>
  dictationCancel(): Promise<void>
  overlayAction(action: 'cancel' | 'confirm'): Promise<void>
  setOverlayMouse(enabled: boolean): Promise<void>
  setLogin(enabled: boolean): Promise<void>
  openExternal(url: string): Promise<void>
  exportData(): Promise<string | null>
  windowMinimize(): void
  windowMaximize(): void
  windowClose(): void
  openHub(page?: string): Promise<void>
  quit(): void
}

export type DeepPartial<T> = {
  [K in keyof T]?: T[K] extends object ? DeepPartial<T[K]> : T[K]
}
