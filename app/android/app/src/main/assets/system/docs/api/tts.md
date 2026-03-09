# TTS API

Text-to-speech via Android TTS engine.

## tts.init

`Permission: device.tts`

Initialize the TTS engine.

**Params:**
- `engine` (string, optional): TTS engine package name (uses system default if omitted)

## tts.voices

`Permission: device.tts`

List available TTS voices.

**Returns:**
- `voices` (array): Each voice has `name`, `locale` (BCP-47), `quality` (integer), `network_required` (boolean)

## tts.speak

`Permission: device.tts`

Speak text on the device speaker.

**Params:**
- `text` (string, required): Text to speak
- `voice` (string, optional): Voice name (from tts.voices)
- `locale` (string, optional): BCP-47 locale tag (e.g. en-US, ja-JP)
- `rate` (float, optional): Speech rate (~0.1..3.0)
- `pitch` (float, optional): Speech pitch (~0.1..3.0)

## tts.stop

`Permission: device.tts`

Stop current TTS speech.
