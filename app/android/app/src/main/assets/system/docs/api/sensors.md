# Sensors API

Permission: `device.sensors` (all actions)

## sensor.list

List available hardware sensors.

**Returns:** `items` array with: `type` (int, Android constant), `string_type` (e.g. `android.sensor.accelerometer`), `name`, `vendor`, `version`, `power_mw`, `resolution`, `max_range`, `min_delay_us`

## sensors.ws.contract

Get WebSocket contract for realtime sensor streaming.

**Returns:** `ws_path` (`/ws/sensors`), `query`, `sample_event`, `ws_message_types`

### WebSocket

Connect to `/ws/sensors` with query params:

| Param | Values | Default |
|-------|--------|---------|
| `sensors` | Comma-separated keys (e.g. `a,g,m`) | required |
| `rate_hz` | 1-1000 | 200 |
| `latency` | `realtime` \| `normal` \| `ui` | `realtime` |
| `timestamp` | `mono` \| `unix` | `mono` |
| `backpressure` | `drop_old` \| `drop_new` | `drop_old` |
| `max_queue` | 64-50000 | 4096 |

**Messages:**
- `hello`: sent on connect -- `{stream_id, sensors, rate_hz, latency, timestamp, backpressure}`
- `sample`: data frame -- `{stream_id, sensor, t, seq, v}` where `v` is `SensorEvent.values` array
- `error`: `{code}` then closes
- `permission_required`: `{request}` then closes
