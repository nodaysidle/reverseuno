import { DEFAULT_SETTINGS } from '@shared/defaults'
import { formatTranscript } from '../src/main/format'
import { loadEnv, requireKey } from './env'

async function main() {
  loadEnv()
  const key = requireKey('OPENROUTER_API_KEY')

  const samples = [
    'um so i think we should uh move the review to thursday at six and then like send the numbers before that',
    'okay so the the deepgram socket dropped but we still have the whole clip buffered so retry it as one batch request instead of replaying packets',
    'hey quick one can you add the nodaysidle dictionary entry for hyprland and make sure the right cmd key still works'
  ]

  for (const raw of samples) {
    const settings = structuredClone(DEFAULT_SETTINGS)
    settings.format.cleanup = 'medium'
    settings.format.style = 'casual'
    const started = Date.now()
    const result = await formatTranscript(raw, settings, key, {
      dictionary: ['nodaysidle', 'Deepgram', 'Hyprland'],
      appClass: 'Ghostty',
      appTitle: 'fish — ~/dev/nodaysidle/reverseuno'
    })
    console.log('\nRAW     ', raw)
    console.log('FORMATTED', result.text)
    console.log(
      'meta     ',
      `${result.latencyMs}ms`,
      result.formatted ? 'formatted' : `fallback (${result.error ?? 'disabled'})`
    )
    if (Date.now() - started > 10_000) console.log('warning: slow request')
  }
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
