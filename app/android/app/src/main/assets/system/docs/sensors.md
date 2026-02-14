# Sensors (Realtime Streams)

me.things exposes device sensors via `device_api` and a WebSocket realtime stream.

## Actions

- `sensor.list` / `sensors.list` (GET): list available sensors (inspect `type`/`string_type`/`name`).
- `sensors.ws.contract` (GET): returns the WebSocket contract (sensor keys, query params, message schema).

## WebSocket `/ws/sensors`

Connect to `ws://127.0.0.1:33389/ws/sensors` with query params to start a realtime sensor stream.

### Query Params

| Param | Required | Default | Description |
|-------|----------|---------|-------------|
| `sensors` | yes | — | Comma-separated sensor keys, e.g. `a,g,m` |
| `rate_hz` | no | `200` | Sample rate (`1..1000`) |
| `latency` | no | `realtime` | `realtime`, `normal`, or `ui` |
| `timestamp` | no | `mono` | `mono` (monotonic) or `unix` (epoch) |
| `backpressure` | no | `drop_old` | `drop_old` or `drop_new` |
| `max_queue` | no | `4096` | Queue size (`64..50000`) |

### Sensor Keys

| Key | Sensor |
|-----|--------|
| `a` | accelerometer |
| `g` | gyroscope |
| `m` | magnetic field |
| `r` | rotation vector |
| `u` | linear acceleration |
| `w` | gravity |
| `x` | geomagnetic rotation vector |
| `y` | game rotation vector |
| `z` | proximity |
| `l` | light |
| `p` | pressure |
| `h` | relative humidity |
| `t` | ambient temperature |
| `s` | step counter |
| `q` | step detector |

### Message Format

Server first sends `hello`, then per-sensor `sample` events:

```json
{ "type":"hello", "stream_id":"s1", "sensors":["a","g","m"], "rate_hz":200, "timestamp":"mono" }
```

```json
{ "type":"sample", "stream_id":"s1", "sensor":"a", "t": 123456.789, "v":[0.01, 9.80, 0.12], "seq": 1001 }
```

- `t` is seconds (monotonic when `timestamp=mono`, unix epoch when `timestamp=unix`).
- `seq` is monotonically increasing per stream.

## Deprecated

Old HTTP stream endpoints (`sensor.stream.*`, `sensors.stream.*`) return `gone`. Use `/ws/sensors` instead.
