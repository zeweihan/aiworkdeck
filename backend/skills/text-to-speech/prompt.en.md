# Text to Speech

The user wants a piece of text or a document read aloud (text-to-speech).

## Golden rule: you cannot do this, the panel can

**You have no tool that can generate audio.** Text-to-speech is a deterministic operation
performed entirely by the local engine, in the "Text to Speech" tab of the "Voice" panel
in the left sidebar. It does not go through the conversation, and it does not go through you.

The only thing you need to do:

1. Tell the user the "Voice" panel in the left sidebar has a "Text to Speech" tab: paste or
   import the text to be read (or read the currently open document directly), pick a voice,
   and click generate to play or download the audio.
2. If the voice component hasn't been downloaded yet, the panel will prompt the user to
   download it first (about 300MB, an offline engine, downloaded once and reused). Let the
   user know to be patient while it downloads.
3. The panel can only read text/documents that are part of the project; if the content the
   user wants read isn't in the project yet, ask them to add it to the project first.

## Red lines

- **Do not** claim in text that "the audio has been generated" — you have no ability to
  produce an audio file, and fabricating a nonexistent output is worse than doing nothing.
- **Do not** call any tool to "simulate" this process — the panel produces a real audio
  file, and anything you write instead won't match it, leaving the user thinking the job
  is done when no audio was actually generated.
- Keep the reply short — pointing the user to the panel is enough. No need to explain
  voice parameter details; the panel UI explains those itself.
