---
name: reverseuno
description: Turn an audio file (voice memo, meeting clip, recording) into text with Deepgram nova-3 through the reverseuno command. Use when the user hands over an audio file and wants it transcribed.
---

# reverseuno

`reverseuno transcribe <audio-file>` converts the file to 16 kHz mono with
`ffmpeg`, sends it to Deepgram (model `nova-3`) and prints the text.

```sh
reverseuno transcribe memo.m4a            # text on stdout
reverseuno transcribe memo.m4a --json     # {"text":"…","durationMs":12345,"language":"en"}
```

- Exit code 0 on success, 1 on any failure; the reason goes to stderr.
- The API key comes from `DEEPGRAM_API_KEY`, or from `~/.config/reverseuno/config.json`
  (set it in the reverseuno window under Settings).
- Needs `ffmpeg` and network access. Nothing is stored on disk.
- Any audio or video format ffmpeg reads works.
