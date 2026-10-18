import { contextBridge, ipcRenderer } from 'electron'
import type {
  AudioDevice,
  DeepPartial,
  DictionaryEntry,
  ReverseunoApi,
  SecretName,
  Settings,
  Snippet
} from '@shared/types'

function subscribe<T>(channel: string, callback: (payload: T) => void): () => void {
  const listener = (_event: Electron.IpcRendererEvent, payload: T) => callback(payload)
  ipcRenderer.on(channel, listener)
  return () => ipcRenderer.removeListener(channel, listener)
}

const api: ReverseunoApi = {
  bootstrap: () => ipcRenderer.invoke('bootstrap'),
  onState: (callback) => subscribe('overlay:state', callback),
  onRefresh: (callback) => {
    const listener = () => callback()
    ipcRenderer.on('hub:refresh', listener)
    return () => ipcRenderer.removeListener('hub:refresh', listener)
  },
  onPage: (callback) => subscribe('hub:page', callback),
  onLevel: (callback) => subscribe('overlay:level', callback),
  state: () => ipcRenderer.invoke('state:get'),

  updateSettings: (patch: DeepPartial<Settings>) => ipcRenderer.invoke('settings:update', patch),
  setSecret: (name: SecretName, value: string) => ipcRenderer.invoke('secret:set', name, value),
  clearSecret: (name: SecretName) => ipcRenderer.invoke('secret:clear', name),
  testProvider: (name) => ipcRenderer.invoke('provider:test', name),
  listDevices: (): Promise<AudioDevice[]> => ipcRenderer.invoke('devices:list'),

  historyUpdate: (id: string, text: string) => ipcRenderer.invoke('history:update', id, text),
  historyDelete: (id: string) => ipcRenderer.invoke('history:delete', id),
  historyClear: () => ipcRenderer.invoke('history:clear'),
  historyCopy: (id: string) => ipcRenderer.invoke('history:copy', id),
  historyInsert: (id: string) => ipcRenderer.invoke('history:insert', id),
  historyFlag: (id: string, flagged: boolean) => ipcRenderer.invoke('history:flag', id, flagged),

  dictionaryAdd: (word: string, correction?: string) =>
    ipcRenderer.invoke('dictionary:add', word, correction) as Promise<DictionaryEntry>,
  dictionaryUpdate: (id: string, patch: Partial<DictionaryEntry>) =>
    ipcRenderer.invoke('dictionary:update', id, patch) as Promise<DictionaryEntry>,
  dictionaryDelete: (id: string) => ipcRenderer.invoke('dictionary:delete', id),

  snippetAdd: (trigger: string, content: string) =>
    ipcRenderer.invoke('snippet:add', trigger, content) as Promise<Snippet>,
  snippetUpdate: (id: string, patch: Partial<Snippet>) =>
    ipcRenderer.invoke('snippet:update', id, patch) as Promise<Snippet>,
  snippetDelete: (id: string) => ipcRenderer.invoke('snippet:delete', id),

  suggestionAccept: (id: string) => ipcRenderer.invoke('suggestion:accept', id),
  suggestionDismiss: (id: string) => ipcRenderer.invoke('suggestion:dismiss', id),

  dictationStart: () => ipcRenderer.invoke('dictation:start'),
  dictationStop: () => ipcRenderer.invoke('dictation:stop'),
  dictationToggle: () => ipcRenderer.invoke('dictation:toggle'),
  dictationCancel: () => ipcRenderer.invoke('dictation:cancel'),
  overlayAction: (action) => ipcRenderer.invoke('overlay:action', action),
  setOverlayMouse: (enabled: boolean) => ipcRenderer.invoke('overlay:mouse', enabled),

  setLogin: (enabled: boolean) => ipcRenderer.invoke('app:set-login', enabled),
  openExternal: (url: string) => ipcRenderer.invoke('app:open-external', url),
  exportData: () => ipcRenderer.invoke('app:export'),

  windowMinimize: () => ipcRenderer.send('window:minimize'),
  windowMaximize: () => ipcRenderer.send('window:maximize'),
  windowClose: () => ipcRenderer.send('window:close'),
  openHub: (page?: string) => ipcRenderer.invoke('window:open-hub', page),
  quit: () => ipcRenderer.send('app:quit')
}

contextBridge.exposeInMainWorld('api', api)
