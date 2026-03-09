# WebView API

Agent-controlled browser with navigation, JS execution, screenshot, and interaction.

## webview.open

Open URL in agent-controlled browser. Blocks until page loads or timeout.

**Params:**
- `url` (string, required): URL to navigate to
- `timeout_s` (integer, optional): max seconds to wait. Default: 30

**Returns:**
- `url` (string): final URL
- `title` (string): page title

## webview.close

Close the browser Activity.

## webview.status

Get current browser state.

**Returns:**
- `open` (boolean): whether browser is open
- `url` (string): current URL
- `title` (string): page title
- `loading` (boolean): loading state
- `width` (integer): viewport width
- `height` (integer): viewport height
- `content_height` (integer): content height
- `can_go_back` (boolean): history back available
- `can_go_forward` (boolean): history forward available

## webview.screenshot

Capture browser screenshot as JPEG.

**Params:**
- `path` (string, optional): output path under user root. Default: `browser/screenshot_<timestamp>.jpg`
- `quality` (integer, optional): JPEG quality 10-100. Default: 80
- `timeout_s` (integer, optional): Default: 10

**Returns:**
- `rel_path` (string): saved file path

## webview.js

Execute JavaScript in the browser page context.

**Params:**
- `script` (string, required): JavaScript code to evaluate
- `timeout_s` (integer, optional): Default: 10

**Returns:**
- `result` (string): evaluation result as string

## webview.tap

Simulate tap at coordinates (touch DOWN + UP).

**Params:**
- `x` (number, required): X coordinate in pixels
- `y` (number, required): Y coordinate in pixels

## webview.scroll

Scroll the browser page by pixel deltas.

**Params:**
- `dx` (integer, optional): horizontal scroll delta. Default: 0
- `dy` (integer, optional): vertical scroll delta. Default: 0

## webview.back

Navigate back in history. No-op if can't go back.

## webview.forward

Navigate forward in history. No-op if can't go forward.

## webview.split

Toggle browser split panel visibility.

**Params:**
- `visible` (boolean, optional): panel visibility. Default: true
- `fullscreen` (boolean, optional): browser takes full screen (chat hidden). Default: false
- `position` (string, optional): `"end"` (bottom/right, default) or `"start"` (top/left)

**Returns:**
- `visible` (boolean): current visibility
- `fullscreen` (boolean): current fullscreen state
- `position` (string): current position

## webview.console

Get buffered JavaScript console messages from all WebViews (chat, browser, agent HTML). Ring buffer holds up to 500 entries.

**Params (query):**
- `since` (integer, optional): only entries with timestamp >= this (epoch ms). Default: 0
- `source` (string, optional): filter by `chat`, `browser`, or `agent_html`
- `limit` (integer, optional): max entries 1-500. Default: 100

**Returns:**
- `entries` (array): objects with `id`, `timestamp`, `level` (log/warn/error/debug/info), `message`, `line`, `source_id`, `source`

## webview.console.clear

Clear all buffered console messages.
