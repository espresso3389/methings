# Embedded Gemma4-E2B-it Integration Plan

## Goal

Add an `Embedded` Brain provider that can run `Gemma4-E2B-it` locally on Android while preserving the current agent runtime:

- background-service friendly
- offline by default
- tool-calling capable
- explicit about multimodal limits

## Why not treat it like another HTTP provider

The current `AgentRuntime` assumes `vendor/base_url/model/api_key` and a streaming HTTP API. That works for OpenAI, Anthropic, Gemini, and OpenRouter, but it is the wrong seam for an embedded model:

- local models do not need API keys
- local execution should survive poor network conditions by design
- scheduling, warmup, memory pressure, and model download lifecycle become part of the app
- multimodal support is a runtime capability question, not just an API payload-shape question

So `Embedded` should be a first-class backend type, not an OpenAI-compatible fake endpoint.

## Recommended architecture

### 1. Split provider routing into `remote` vs `embedded`

Keep the current HTTP providers as-is. Add a separate embedded execution path:

- `ProviderKind.EMBEDDED`
- `EmbeddedModelCatalog`
- future `EmbeddedInferenceBackend`

The backend should return a normalized result:

- assistant text chunks
- tool calls
- capability flags: `image`, `audio`

That lets the outer agent loop stay mostly unchanged.

### 2. Use Gemma4-E2B-it as the local planner / tool-caller

`Gemma 4` on Android is positioned by Google as a local agentic model with advanced reasoning and native tool use, and the April 2, 2026 Android Developers blog says the AICore Developer Preview is intended to let developers prototype directly on-device with `Gemma 4 E2B` and `E4B`. It also says ML Kit GenAI Prompt API support is still expanding, which implies capability gaps should be expected in the preview stage.

Inference: `Gemma4-E2B-it` should be treated as the primary embedded text-and-tools model first, not as the sole multimodal engine.

### 3. Do not force multimodal through Gemma4-E2B-it if the runtime is text-only

For this app, the best optimization is:

- use `Gemma4-E2B-it` for planning, reasoning, and tool selection
- keep image/audio understanding as separate tools unless the chosen embedded runtime proves strong and stable for inline multimodal inputs

Why:

- the existing architecture already has tool-mediated media handling
- agentic quality depends more on reliable tool calling than on forcing every image/audio byte into the main context
- memory pressure on Android is much lower if the main planner stays text-centric

Recommended local multimodal path:

- `analyze_image` -> local vision tool/runtime
- `analyze_audio` -> local audio/STT tool/runtime
- feed structured results back to Gemma as tool output

This is usually better than a single large VLM in a background service.

### 4. Prefer an app-managed runtime over AICore as the only backend

Official Android docs say:

- the Google AI Edge SDK / AICore gives OS-managed model distribution and hardware acceleration
- ML Kit GenAI Prompt API supports multimodal prompts
- AICore has important execution constraints, including background limitations

That makes AICore valuable, but not sufficient as the only backend for this app, because this app explicitly requires background service execution.

Recommended priority:

1. App-managed embedded runtime for the main agent loop
2. Optional AICore acceleration path when device/runtime allows it
3. Same normalized `EmbeddedInferenceBackend` contract for both

## Backend recommendation

For a production-quality embedded provider in this app, target this order:

1. `LiteRT` / Google AI Edge style local backend for Gemma4-E2B-it
2. optional AICore fast path on supported devices
3. do not use the Python worker as the primary inference path unless no native runtime is viable

Why not Python-first:

- startup cost is worse
- memory duplication is harder to control
- Android lifecycle integration is weaker
- JNI/native delegates are where device acceleration lives

The embedded Python worker can still be useful for:

- model download / conversion helpers
- offline asset preparation
- debugging

## Concrete implementation phases

### Phase 1

- add `Embedded` to Brain settings
- allow API-keyless config for embedded
- introduce `ProviderKind.EMBEDDED`
- add model catalog entry for `gemma4-e2b-it`
- do not advertise multimodal support yet
- standardize local model placement under `files/user/models/embedded/<model>/`

### Phase 2

- add `EmbeddedInferenceBackend`
- implement model lifecycle: install, load, warm, unload, health
- add memory-pressure handling and warm model cache
- normalize embedded tool-calling outputs into the existing agent loop

### Phase 3

- add local image/audio helper runtimes and expose them through existing tools
- only add inline multimodal input to the embedded provider if measured quality/latency is clearly better

### Phase 4

- optional AICore path on supported devices
- capability negotiation at runtime:
  - `supports_tool_calling`
  - `supports_image_input`
  - `supports_audio_input`
  - `supports_background_execution`

## Performance guidance

- Keep `Gemma4-E2B-it` as the always-on planner model.
- Avoid shipping a monolithic multimodal runtime in the first iteration.
- Cache tokenizer/model state once per process.
- Add explicit warmup on service start or first use.
- Bound context windows more aggressively than cloud defaults.
- Treat image/audio as tool outputs unless the embedded backend proves it can handle them efficiently.

## Current repo status

This repo now has:

- the config/runtime seams for an `Embedded` provider
- a backend registry abstraction
- model install status detection
- a prompt-driven local tool-calling bridge that keeps embedded turns inside the existing agent/tool loop
- a bounded two-phase local tool flow:
  - plan tool names first
  - normalize arguments per selected tool
- duplicate planned tool names are suppressed while preserving order
- oversized/noisy model outputs are normalized before parse/repair
- LiteRT backend instance caching with warm/load state reporting
- service-start warmup for the configured embedded model
- memory-pressure unload handling wired from the Android service lifecycle
- embedded backend diagnostics surfaced through status/UI, including:
  - final response source (`original`, `repaired`, `fallback`)
  - final tool/message counts
  - per-tool normalization failures
  - repair/fallback usage
- a fixed on-device placement convention:
  - `files/user/models/embedded/gemma4-e2b-it/model.litertlm`
  - or `model.task`
  - or `model.tflite`

It still does not have a production-grade native Gemma tool-calling SDK path yet. The normalized embedded backend seam now exists in code, and the current LiteRT-backed implementation fulfills it with a structured JSON prompt/response contract behind that backend boundary. Production should still replace that prompt-driven implementation with a native structured output/tool-calling path when the runtime supports it.

What remains external to this repo:

- a native structured tool-calling/runtime API from the underlying embedded stack
- a second embedded backend implementation that can replace the prompt-driven LiteRT path behind the same registry seam

## Current install flow

The app now supports two provisioning paths for embedded model files:

- import a local file through the Android document picker
- download a file from a direct `http` or `https` URL

Both paths save into:

- `files/user/models/embedded/gemma4-e2b-it/model.litertlm`
- or `model.task`
- or `model.tflite`
- or `model.bin`

For Hugging Face downloads, use direct file URLs under `/resolve/...`.
Do not use `/blob/...` URLs from the web UI, because those return HTML pages rather than raw model bytes.

## Troubleshooting

When checking embedded status or the WebView Embedded panel, these fields are the fastest way to understand what happened on the last turn:

- `response_source`
  - `original`: the first merged embedded result was accepted
  - `repaired`: the backend had to run one repair pass and used that repaired result
  - `fallback`: required-tool execution failed and the backend returned the explicit blocker message
- `tool_failures`
  - `no_output`: the per-tool argument pass returned nothing usable
  - `invalid_arguments`: the model returned arguments that failed schema validation
  - `required_tool_fallback`: the turn ended in the required-tool fallback path
- `final_tool_call_count`
  - how many tool calls the embedded backend finally returned to the agent loop
- `final_message_count`
  - how many assistant text messages were returned in that final embedded result
- `repair_attempt_count`
  - how many repair generations were actually consumed in the last turn

Common patterns:

- `response_source=original` with `final_tool_call_count>0`
  - normal successful local tool planning
- `response_source=repaired`
  - the first output was malformed or too weak, but repair recovered it
- `response_source=fallback` with `tool_failures`
  - the embedded backend could not produce valid required tool calls; inspect the listed failure reasons first
- `final_tool_call_count=0` and `final_message_count=1`
  - the model returned assistant text only; this may be correct for non-tool turns, but suspicious for tool-heavy tasks

## Sources

- Android Developers blog, “Gemma 4: The new standard for local agentic intelligence on Android” (April 2, 2026): https://developer.android.com/blog/posts/gemma-4-the-new-standard-for-local-agentic-intelligence-on-android
- Android Developers, “Google AI Edge SDK”: https://developer.android.com/ai/gemini-nano/ai-edge-sdk
- Android Developers, “ML Kit GenAI APIs”: https://developer.android.com/ai/gemini-nano/ml-kit-genai
