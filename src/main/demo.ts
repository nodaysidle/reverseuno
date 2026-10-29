import type { DictationEntry } from '@shared/types'
import type { Store } from './store'

const DEMO: { text: string; app: string; title: string; hoursAgo: number; seconds: number }[] = [
  {
    text: 'Quick note before the standup: the streaming fallback should retry the whole clip in one request, not replay it packet by packet.',
    app: 'code',
    title: 'reverseuno — dictation.ts',
    hoursAgo: 0.4,
    seconds: 9.2
  },
  {
    text: "Let's move the review to Thursday at 6 PM. I'll send the quarterly numbers beforehand.",
    app: 'Slack',
    title: 'Slack | nodaysidle',
    hoursAgo: 2.1,
    seconds: 6.8
  },
  {
    text: 'Add a hydration guard to the clipboard snapshot, and skip restoring if the clipboard changed while the paste was in flight.',
    app: 'cursor',
    title: 'clipboard.ts — reverseuno',
    hoursAgo: 4.6,
    seconds: 10.4
  },
  {
    text: 'The dictionary should prioritise starred words when we send keyterms to Deepgram, otherwise the vocabulary list gets truncated by recency.',
    app: 'Ghostty',
    title: 'fish — ~/dev/nodaysidle/reverseuno',
    hoursAgo: 22,
    seconds: 12.1
  },
  {
    text: 'Thanks for the walkthrough — the overlay feels much calmer now that it only appears while recording.',
    app: 'Mail',
    title: 'Re: overlay polish',
    hoursAgo: 27,
    seconds: 7.3
  },
  {
    text: 'Draft the release note: Right Super to talk, release to paste. No window switching, no copy-paste dance.',
    app: 'Notion',
    title: 'Release notes',
    hoursAgo: 49,
    seconds: 8.7
  },
  {
    text: 'Check whether the Apple Magic Keyboard sends Super_R on the right Command key, then bind press and release separately.',
    app: 'code',
    title: 'hyprland.lua',
    hoursAgo: 72,
    seconds: 9.9
  }
]

export function seedDemo(store: Store): void {
  if (store.history.length > 0) return
  const now = Date.now()
  for (const item of [...DEMO].reverse()) {
    const createdAt = now - item.hoursAgo * 3600_000
    const entry: DictationEntry = {
      id: `demo-${createdAt}`,
      text: item.text,
      createdAt,
      provider: 'deepgram',
      model: 'nova-3',
      language: 'en',
      durationMs: item.seconds * 1000,
      asrMs: 380 + Math.round(item.seconds * 22),
      formatMs: 410 + Math.round(item.seconds * 12),
      totalMs: item.seconds * 1000 + 900,
      appClass: item.app,
      appTitle: item.title,
      cleanup: 'light',
      style: 'casual',
      inserted: true,
      wordCount: item.text.split(/\s+/).length
    }
    // addHistory unshifts; insert in reverse so the list reads newest first.
    store.addHistory(entry)
  }
  store.addDictionary('nodaysidle')
  store.addDictionary('Deepgram')
  store.addDictionary('Hyprland')
  store.addDictionary('reverseuno')
  store.addDictionary('keyterms')
  store.addDictionary('Wispr')
  store.addDictionary('clipboard')
  store.addSnippet('my calendar link', 'https://cal.com/nodaysidle')
  store.addSnippet('sign off', 'Thanks — talk soon.\n\n— Arch')
  store.addSnippet('address', 'Bohinj, Slovenia')
}
