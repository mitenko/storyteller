# Storyteller App — Design Spec
**Date:** 2026-03-23

## Overview

Storyteller is a Flutter app for iOS and Android that lets users — primarily children — hold their device over a page of a graphic novel or storybook, snap a photo, and have the text read aloud with distinct AI-generated voices mapped to each character. The user taps individual text segments on screen to trigger playback; each segment is voiced by the character who speaks it.

---

## 1. Architecture Overview

The app has four logical layers:

1. **Camera & OCR** — Flutter camera plugin captures a photo; Google ML Kit Text Recognition (v2) runs on-device to extract raw text and bounding box positions.
2. **AI Parsing** — The raw OCR text (plus bounding box positions as spatial hints) is sent to the Claude API. Claude identifies who is speaking each line via dialogue attribution (e.g., `"said the Wolf"`) and visual layout hints for comic speech bubbles. Returns a structured JSON list of speech units in reading order.
3. **Voice Engine** — Each identified character maps to an ElevenLabs voice ID. Voices for all new characters are pre-fetched after parsing (before the user taps anything). Audio is cached locally after first fetch. The app streams audio per tapped speech unit.
4. **Persistence** — A local SQLite database (via `drift`) stores the character→voice mapping. First encounter triggers an ElevenLabs voice selection; all subsequent uses are local lookups. Cached audio files are stored in the app's temporary directory.

**Connectivity:** Requires Wi-Fi. Offline mode is out of scope for now.
**Profiles:** Single user profile.
**API Keys:** Both Claude and ElevenLabs API keys are injected at build time via `--dart-define` (e.g., `--dart-define=CLAUDE_API_KEY=xxx --dart-define=ELEVENLABS_API_KEY=xxx`). Keys are never hardcoded in source. The app reads them via `const String.fromEnvironment(...)`.

---

## 2. Core Components

| Component | Responsibility |
|---|---|
| `CameraScreen` | Full-screen viewfinder, capture button, preview with "Retake" and "Read This Page" buttons, camera permission handling |
| `OcrService` | Wraps ML Kit Text Recognition v2; returns text blocks with bounding boxes sorted by Y-position (top to bottom) |
| `ParsingService` | Sends OCR output to Claude API; returns `List<SpeechUnit>` in reading order |
| `VoiceMapRepository` | SQLite-backed (drift); maps character name → ElevenLabs voice ID; on first encounter, selects a random voice from the available ElevenLabs voices list and saves it |
| `AudioCacheRepository` | Caches ElevenLabs audio files to the app's temporary directory keyed by `(voiceId, textHash)`; returns cached file if present, otherwise fetches from ElevenLabs |
| `PlaybackService` | Given a single speech unit, resolves audio via `AudioCacheRepository` and plays it using `just_audio`; supports play/stop; falls back to device TTS (`flutter_tts`) on ElevenLabs failure |
| `ReaderScreen` | Displays all parsed speech units as tappable cards labelled with speaker name; tapping triggers playback; highlights active card during playback; shows a loading indicator if voice pre-fetch is still in progress |

### SpeechUnit model
```dart
class SpeechUnit {
  final int index;          // 0-based reading order as returned by Claude
  final String speaker;     // "Narrator" or a character name
  final String text;
}
```

---

## 3. Claude API Integration

### System prompt
```
You are a reading assistant for a children's storybook app.
Given OCR text extracted from a book page, identify each speech unit and who is speaking it.
A speech unit is one continuous piece of dialogue or narration.
Return ONLY a JSON array — no markdown, no explanation.
```

### User message format
```
OCR text blocks (in order of Y position, top to bottom):

[BLOCK 1] (y=42): "Once upon a time," said the Wolf.
[BLOCK 2] (y=87): "Get away!" cried Little Red.
...

Return a JSON array of speech units in reading order:
[{"speaker": "Narrator" | "<character name>", "text": "<exact text>"}]

Rules:
- Use "Narrator" for descriptive text not attributed to a character.
- Use the character name exactly as it appears in the text.
- If a speaker cannot be determined, use "Narrator".
- Do not merge or split the original text.
```

### Expected response
```json
[
  {"speaker": "Narrator", "text": "Once upon a time,"},
  {"speaker": "Wolf", "text": "Get away!"},
  {"speaker": "Little Red", "text": "No!"}
]
```

### Response parsing & error handling
- Parse response as JSON. If parsing fails (malformed JSON, missing fields, empty array), show an error message: "Couldn't understand this page — please try again." with a "Retry" button that re-sends the same OCR output. If retry also fails, return user to CameraScreen.
- Unknown or empty speaker values (`""`, `null`) are treated as `"Narrator"`.
- The `index` field in `SpeechUnit` is assigned in array order (0, 1, 2…).

---

## 4. Data Flow

1. User opens app → **CameraScreen** (full-screen viewfinder)
2. User taps capture → photo taken → preview shown with "Retake" and "Read This Page" buttons
   - "Retake" returns to the viewfinder
   - No automatic quality/blur check; user judges quality visually
3. User taps "Read This Page" → **OcrService** runs on-device → returns text blocks with bounding boxes
4. If OCR returns no text → show message "No text found — try holding the camera steadier or in better light" → return to CameraScreen
5. Text blocks sent to **ParsingService** → Claude API call → returns `List<SpeechUnit>`
6. If Claude call fails → show retry button; after 2 failures show error and return to CameraScreen
7. **VoiceMapRepository** pre-fetches voices for all characters not yet in the database:
   - For each new character: call ElevenLabs `/voices`, pick a random voice from the list, save to SQLite
   - This happens before navigation so the user never waits at tap time
8. Navigate immediately to **ReaderScreen**; voice pre-fetch runs concurrently. **ReaderScreen** shows a loading indicator ("Getting voices ready…") until pre-fetch resolves, then enables card tapping. — renders each `SpeechUnit` as a tappable card (speaker name + text), in `index` order
9. User taps a card → **AudioCacheRepository** checks cache by `(voiceId, textHash)`
   - Cache hit → return cached audio file
   - Cache miss → fetch from ElevenLabs TTS API, save to temporary directory, return file
10. **PlaybackService** plays the audio with `just_audio`; tapped card highlights during playback
11. Tapping a new card while audio is playing stops current audio and starts the new card
12. Tapping the active card while playing stops playback (toggle)

---

## 5. Error Handling

| Scenario | Behavior |
|---|---|
| Camera permission denied | Friendly message explaining why camera is needed; button to open device settings |
| OCR returns no text | "No text found — try holding the camera steadier or in better light"; return to CameraScreen |
| Claude API network/server error | Retry button; after 2 failures return to CameraScreen |
| Claude returns malformed or empty JSON | "Couldn't understand this page — please try again" with retry; after 2 failures return to CameraScreen |
| Unknown/null speaker from Claude | Treat as "Narrator" |
| ElevenLabs `/voices` fetch fails during pre-fetch | Assign `flutter_tts` device TTS for that character (no voice ID saved); log failure silently |
| ElevenLabs TTS fetch fails at playback | Fall back to `flutter_tts` for that speech unit; don't block the user |
| No internet before Claude call | Detect connectivity; show "No Wi-Fi connection" message before attempting any API call |
| Audio cache write fails | Play from stream without caching; retry cache write next time |

---

## 6. Testing

| Layer | Approach |
|---|---|
| `OcrService` | Integration tests on a real device or emulator using Flutter's integration test package; test with bundled sample images (clear text, comic layout). Cannot be meaningfully unit tested without a real ML Kit platform channel — do not mock at unit level. |
| `ParsingService` | Unit tests using a mocked HTTP client; test correct JSON parsing, empty-speaker normalization, malformed response handling, and retry logic |
| `VoiceMapRepository` | Unit tests using an in-memory drift database; test first-time voice assignment, repeat lookup, persistence across restarts, and unknown-speaker normalization |
| `AudioCacheRepository` | Unit tests with a mocked ElevenLabs client and a temp directory; test cache hit, cache miss, and cache write failure |
| `PlaybackService` | Unit tests mocking `AudioCacheRepository` and `just_audio`; test play, stop, toggle, and device TTS fallback |
| `ReaderScreen` | Widget tests verifying cards render in index order, highlight on tap, stop on re-tap, show loading indicator during pre-fetch |
| Integration | End-to-end flow test: bundled sample image → OcrService → mocked Claude response → VoiceMapRepository → mocked ElevenLabs → ReaderScreen tap → PlaybackService |

---

## 7. Key Dependencies

| Package | Purpose |
|---|---|
| `camera` | Camera access |
| `google_mlkit_text_recognition` | On-device OCR |
| Claude API (via `http`) | Character/dialogue parsing |
| ElevenLabs API (via `http`) | AI voice generation |
| `drift` + `sqlite3_flutter_libs` | Local persistence for voice map |
| `flutter_tts` | Device TTS fallback |
| `just_audio` | Audio streaming and playback |
| `crypto` | SHA-256 hashing for audio cache keys |
| `path_provider` | Resolve temporary directory for audio cache |
| `connectivity_plus` | Detect Wi-Fi before API calls |

---

## 8. Out of Scope (v1)

- Offline mode
- Multiple user profiles
- Book/story recognition
- Automatic sequential playback
- User-configurable voice assignments
- Blur/quality detection on captured photo
- Persistent audio cache across app reinstalls
