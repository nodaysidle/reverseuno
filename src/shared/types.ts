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
  | 'skipped'
  | 'error'
  | 'cancelled'

export interface AppRule {
  pattern: string
  keys: PasteKeys
  restoreDelayMs?: number
}

export interface Settings {
  stt: {
    provider: SttProvider
    /** Deepgram model, used when provider is 'deepgram'. */
    model: string
    /** OpenRouter transcription model id, used when provider is 'openrouter'. */
    openrouterModel: string
    language: string
    smartFormat: boolean
    punctuate: boolean
    keytermLimit: number
    endpointingMs: number
    utteranceEndMs: number
  }
  format: {
    enabled: boolean
    provider: 'openrouter'
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
  history: {
    maxEntries: number
    autoLearn: boolean
  }
  system: {
    launchAtLogin: boolean
    openHubOnStart: boolean
  }
  appearance: {
    /** 'omarchy' follows the active Omarchy theme; 'paper' is the built-in light look. */
    theme: 'omarchy' | 'paper'
  }
}

/** The colors the hub needs, taken from Omarchy's current colors.toml. */
export interface ThemePalette {
  name: string
  mode: 'light' | 'dark'
  background: string
  foreground: string
  accent: string
  muted: string
  surface: string
  deep: string
  red: string
  green: string
  violet: string
}

export type SttProvider = 'deepgram' | 'openrouter'

export type ModelKind = 'format' | 'stt'

export interface ModelOption {
  id: string
  name: string
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

export type SecretName = 'deepgram' | 'openrouter'

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
  /** How the session was started: the push-to-talk key (has a release) or a click/tray (needs an explicit stop). */
  source?: 'key' | 'ui'
  /** False when the session saves to history instead of pasting (started from the hub). */
  insert?: boolean
  /** Overlay should play start/stop cues (settings.overlay.sounds). */
  sounds?: boolean
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
  paths: { config: string; history: string; log: string; cli: string }
  /** Null when Omarchy isn't installed or its colors can't be read. */
  theme: ThemePalette | null
  /** First-run checks the hub can't make from the renderer. */
  setup: { hotkeyBound: boolean }
}

export interface ActionResult {
  ok: boolean
  error?: string
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
  onTheme(callback: (theme: ThemePalette | null) => void): () => void
  state(): Promise<StateEvent>
  updateSettings(patch: DeepPartial<Settings>): Promise<Settings>
  setSecret(name: SecretName, value: string): Promise<SecretStatus[]>
  clearSecret(name: SecretName): Promise<SecretStatus[]>
  testProvider(name: 'deepgram' | 'openrouter'): Promise<ProviderTestResult>
  listDevices(): Promise<AudioDevice[]>
  listModels(kind: ModelKind): Promise<ModelOption[]>
  historyUpdate(id: string, text: string): Promise<{ entry: DictationEntry; suggestions: Suggestion[] }>
  historyDelete(id: string): Promise<void>
  historyClear(): Promise<void>
  historyCopy(id: string): Promise<ActionResult>
  historyInsert(id: string): Promise<ActionResult>
  historyFlag(id: string, flagged: boolean): Promise<void>
  dictionaryAdd(word: string, correction?: string): Promise<DictionaryEntry>
  /** Adds the built-in developer word pack; resolves to how many words were new. */
  dictionaryAddPack(): Promise<number>
  dictionaryUpdate(id: string, patch: Partial<DictionaryEntry>): Promise<DictionaryEntry>
  dictionaryDelete(id: string): Promise<void>
  snippetAdd(trigger: string, content: string): Promise<Snippet>
  snippetUpdate(id: string, patch: Partial<Snippet>): Promise<Snippet>
  snippetDelete(id: string): Promise<void>
  suggestionAccept(id: string): Promise<void>
  suggestionDismiss(id: string): Promise<void>
  /** Start, or stop and finish. insert=false saves to history without pasting. */
  dictationToggle(insert?: boolean): Promise<void>
  dictationCancel(): Promise<void>
  setLogin(enabled: boolean): Promise<void>
  openExternal(url: string): Promise<void>
  exportData(): Promise<string | null>
  windowMinimize(): void
  windowMaximize(): void
  windowClose(): void
  openHub(page?: string): Promise<void>
  quit(): Promise<void>
}

export type DeepPartial<T> = {
  [K in keyof T]?: T[K] extends object ? DeepPartial<T[K]> : T[K]
}
