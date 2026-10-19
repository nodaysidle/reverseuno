import { FORMAT_MODELS, OPENROUTER_STT_MODELS } from '@shared/defaults'
import type { ModelKind, ModelOption } from '@shared/types'
import { log } from './logger'

const CACHE_MS = 30 * 60 * 1000

const cache = new Map<ModelKind, { at: number; models: ModelOption[] }>()

function fallback(kind: ModelKind): ModelOption[] {
  const ids = kind === 'stt' ? OPENROUTER_STT_MODELS : FORMAT_MODELS
  return ids.map((id) => ({ id, name: id }))
}

/**
 * OpenRouter's model catalogue. Transcription models are not in the default
 * listing; they only appear with output_modalities=transcription. Text models
 * are filtered to those that take text in and give text out.
 */
export async function listOpenRouterModels(kind: ModelKind, baseUrl: string): Promise<ModelOption[]> {
  const cached = cache.get(kind)
  if (cached && Date.now() - cached.at < CACHE_MS) return cached.models

  const base = (baseUrl || 'https://openrouter.ai/api/v1').replace(/\/+$/, '')
  const url = kind === 'stt' ? `${base}/models?output_modalities=transcription` : `${base}/models`
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(8000) })
    if (!response.ok) throw new Error(`models ${response.status}`)
    const data = (await response.json()) as {
      data?: {
        id?: string
        name?: string
        architecture?: { input_modalities?: string[]; output_modalities?: string[] }
      }[]
    }
    const models = (data.data ?? [])
      .filter((item): item is { id: string; name?: string; architecture?: typeof item.architecture } =>
        typeof item.id === 'string'
      )
      .filter((item) => !item.id.endsWith(':batch'))
      .filter((item) => {
        if (kind === 'stt') return true
        const input = item.architecture?.input_modalities ?? ['text']
        const output = item.architecture?.output_modalities ?? ['text']
        return input.includes('text') && output.includes('text')
      })
      .map((item) => ({ id: item.id, name: item.name || item.id }))
      .sort((a, b) => a.id.localeCompare(b.id))
    if (models.length === 0) throw new Error('empty model list')
    cache.set(kind, { at: Date.now(), models })
    return models
  } catch (error) {
    log.warn('[models] could not load OpenRouter models; using fallbacks', String(error))
    return fallback(kind)
  }
}
