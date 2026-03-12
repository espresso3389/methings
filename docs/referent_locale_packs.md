# Generated Referent Locale Packs

## Goal

Support pronoun and deictic reference resolution for user-provided artifacts across languages without hardcoding language-specific words in runtime code.

Examples:

- "this photo"
- "these files"
- "it"
- "them"
- "この写真"
- "これ"
- "estos archivos"

The runtime should remain deterministic. AI may generate locale packs on demand, but the generated output must be data only and must conform to a strict schema.

## Product Behavior

When the user refers to previously attached artifacts using deictic language, the agent should resolve those references against recent artifact groups and inject the resolved artifact(s) into the current turn.

Examples:

- User sends one image, then says "Can you crop this?"
  Resolve `this` to the most recent image group.
- User sends three PDFs, then says "summarize these"
  Resolve `these` to the most recent multi-file document group.
- User sends an image, then a ZIP, then says "open it"
  Prefer the most recent singular artifact unless the utterance contains a stronger type hint such as `photo`, `image`, `zip`, `PDF`, `写真`.

## Non-Goals

- No free-form runtime code generation.
- No LLM call on every turn just to interpret `this` / `it`.
- No unbounded replay of all previous attachments into every provider payload.
- No locale-specific string matching spread across Kotlin or JS.

## Architecture

Split the system into four parts:

1. Artifact memory
   Track recent user-provided artifact groups.
2. Locale pack
   Data-only lexicon and patterns for one locale.
3. Deterministic resolver
   Turn text + locale pack + recent artifact groups -> resolved referent set.
4. Optional on-demand locale generation
   If no locale pack exists for the detected language, generate one once, validate it, cache it, then use it.

## Artifact Memory Model

Track artifacts by turn, not just as a flat recent-file list.

Suggested record:

```json
{
  "group_id": "turn_1773219617295",
  "session_id": "default",
  "turn_ts": 1773219617295,
  "source": "user",
  "artifacts": [
    {
      "artifact_id": "a1",
      "kind": "image",
      "rel_path": "captures/chat/chat_capture_1773209710007.jpg",
      "name": "chat_capture_1773209710007.jpg",
      "mime_type": "image/jpeg"
    }
  ]
}
```

Suggested retention:

- Keep last 8 to 12 groups per session.
- Prefer recency.
- Preserve grouping so plural references can map to "the files from that turn".

## Locale Pack Schema

Locale packs should be JSON data under a stable directory such as:

- `user/lib/referents/locales/en.json`
- `user/lib/referents/locales/ja.json`
- `user/lib/referents/locales/es.json`

Suggested schema:

```json
{
  "schema_version": 1,
  "locale": "es",
  "display_name": "Spanish",
  "generated": true,
  "generated_at": "2026-03-12T00:00:00Z",
  "generator": {
    "kind": "sub_agent",
    "model": "gpt-5"
  },
  "tokens": {
    "deictic_singular": ["this", "that", "it"],
    "deictic_plural": ["these", "those", "them"],
    "proximal_singular": [],
    "proximal_plural": [],
    "distal_singular": [],
    "distal_plural": []
  },
  "artifact_terms": {
    "image": ["image", "photo", "picture", "screenshot"],
    "document": ["document", "pdf", "file"],
    "audio": ["audio", "recording", "voice note"],
    "video": ["video", "clip"],
    "archive": ["zip", "archive"],
    "code": ["code", "script", "file"]
  },
  "patterns": [
    {
      "id": "this_image",
      "kind": "singular",
      "artifact_bias": "image",
      "phrases": ["this image", "this photo", "that picture"]
    },
    {
      "id": "these_documents",
      "kind": "plural",
      "artifact_bias": "document",
      "phrases": ["these files", "these documents"]
    }
  ],
  "ambiguity_rules": {
    "prefer_most_recent_group": true,
    "prefer_explicit_artifact_term_over_bare_pronoun": true,
    "allow_bare_singular_pronoun": true,
    "allow_bare_plural_pronoun": true
  },
  "examples": [
    {
      "text": "Can you crop this image?",
      "expected": {
        "count": 1,
        "artifact_bias": "image"
      }
    }
  ]
}
```

## Runtime Resolution Flow

1. Detect dominant language for the user turn.
2. Load locale pack for that language.
3. If no pack exists:
   - generate pack on demand
   - validate it
   - cache it
   - continue
4. Parse the user text into normalized features:
   - singular/plural deictic markers
   - artifact-kind hints
   - explicit filenames or extensions
5. Score recent artifact groups.
6. Resolve zero, one, or many artifact candidates.
7. Inject only the resolved artifact set into the current turn.

## Resolver Scoring

Suggested scoring features:

- `+50` exact filename match
- `+30` explicit artifact-kind phrase match
- `+20` bare singular deictic against most recent singular group
- `+20` bare plural deictic against most recent multi-artifact group
- `+10` proximity marker
- `+5` recency bonus
- `-20` artifact-kind mismatch
- `-30` stale group beyond short horizon

Suggested outputs:

- `resolved_single`
- `resolved_group`
- `ambiguous`
- `unresolved`

If ambiguous:

- do not silently inject a random artifact
- either fall back to the most recent high-confidence match if score gap is large enough
- or ask a concise clarification question

## Injection Rules

The resolved artifact set should be treated as if it were attached to the current turn.

For providers with native media support:

- images: inject as multimodal input blocks
- audio: inject if provider supports inline audio

For non-native binary/document support:

- inject structured file references into the turn text or tool context
- avoid sending full binary unless needed
- let the agent use tools to inspect the referenced files

Important:

- Only inject artifacts selected by the resolver.
- Do not replay every historical attachment.
- New current-turn attachments always override historical referents.

## On-Demand Locale Generation

Dynamic generation is allowed, but only for locale data.

Recommended flow:

1. Runtime detects language `es`.
2. No `es.json` locale pack exists.
3. Runtime calls a sub-agent tool:
   - input: schema, target locale, a few English anchor concepts
   - output: JSON matching schema
4. Validator checks:
   - valid JSON
   - schema completeness
   - arrays contain strings only
   - no duplicate phrases after normalization
   - no excessive size
5. Runtime stores:
   - `user/lib/referents/locales/es.json`
   - optional sidecar metadata or debug log
6. Resolver loads pack and continues.

Storage policy:

- Locale packs are stored per device.
- They are not synced across devices or accounts in the first version.
- Generated packs may differ across devices depending on generation time and model behavior.

## Safety Constraints For Generated Packs

Generated packs must be:

- data only
- schema-limited
- size-bounded
- cached locally
- replaceable
- inspectable in logs or filesystem
- refreshable after expiration

Generated packs must not:

- contain code
- contain regexes unless schema explicitly allows a constrained subset
- write arbitrary runtime rules
- directly trigger tools

## Validation Rules

Before a generated locale pack is accepted:

- `schema_version` must be supported
- `locale` must be a simple BCP-47-like tag
- all token lists must be arrays of short strings
- all `artifact_terms` keys must be from an allowlist
- `patterns[].kind` must be `singular` or `plural`
- phrase count and total size must be capped
- normalized duplicates must be removed

Recommended caps:

- max 64 tokens per category
- max 128 patterns
- max 32 examples
- max file size 64 KB

## Expiration And Refresh

Generated locale packs should expire and refresh after a period.

Recommended metadata fields:

```json
{
  "generated_at": "2026-03-12T00:00:00Z",
  "expires_at": "2026-06-10T00:00:00Z"
}
```

Recommended first-version behavior:

- Built-in locale packs do not expire.
- Generated locale packs expire after a fixed TTL.
- Suggested TTL: 30 to 90 days.
- On lookup, if a generated pack is expired:
  - continue using it temporarily if it still validates
  - schedule background regeneration
  - replace it atomically when the new pack passes validation
- If regeneration fails:
  - keep the previous valid pack
  - log the failure

This avoids breaking conversations because a refresh attempt failed.

## Observability

Log the following:

- detected locale
- whether pack was built-in or generated
- locale generation success/failure
- resolver decision and confidence
- chosen artifact group IDs
- whether the resolver fell back or asked clarification
- locale-pack age, expiration status, and refresh result

This is important because bad referent resolution is otherwise hard to debug.

## Fallback Behavior

If locale generation fails or validation rejects the result:

- fall back to a minimal universal heuristic:
  - explicit filenames
  - extensions like `.pdf`, `.jpg`, `.png`, `.zip`
  - current-turn attachments only
- do not pretend the referent was resolved
- ask a short clarification if needed

## Confidence Thresholding

The resolver should support thresholding so low-confidence locale packs or weak matches do not silently hijack references.

Recommended thresholds:

- `pack_quality_threshold`
  Minimum accepted quality for a generated locale pack.
- `resolution_confidence_threshold`
  Minimum score required to auto-resolve a referent.
- `ambiguity_gap_threshold`
  Minimum score gap between top candidate and runner-up required to avoid clarification.

Suggested first-version behavior:

- If pack quality is below threshold:
  - do not use locale-specific deictic rules from that pack
  - fall back to universal heuristics
- If referent resolution confidence is below threshold:
  - do not auto-inject historical artifacts
  - ask a concise clarification question if needed
- If the top two candidates are too close:
  - mark as ambiguous
  - do not guess

Pack quality signals may include:

- schema completeness
- duplicate or conflicting tokens
- generated example coverage
- validator warnings
- past runtime success/failure telemetry on that device

## Suggested Integration Points

Likely runtime touchpoints:

- Chat UI attachment submission flow:
  Record artifact groups when attachments are sent.
- Agent runtime turn building:
  Before `buildInitialInput(...)`, resolve referents for the current user text.
- Provider input builder:
  Inject resolved artifacts as current-turn inputs.

Relevant current code:

- Attachment serialization in chat UI:
  `app/android/app/src/main/assets/www/index.html`
- Current-turn media extraction:
  `app/android/app/src/main/java/jp/espresso3389/methings/service/agent/AgentRuntime.kt`

## Recommended Rollout

1. Add artifact-group tracking.
2. Add deterministic referent resolver with built-in `en` and `ja`.
3. Add locale pack loader and validator.
4. Add dynamic sub-agent generation for missing locales.
5. Add resolver logs and a debug viewer.
6. Add tests for English, Japanese, and one generated locale.

## Open Questions

- Should pack refresh happen inline on demand, or only through a background job after expiry?
- Should telemetry influence threshold tuning per locale over time?
- Should the app eventually expose debug-only inspection for generated locale packs even if normal UI is omitted?
