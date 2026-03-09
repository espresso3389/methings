# MCU API

MCU programming and serial communication over USB.

All actions except `mcu.models` and `mcu.flash_plan` require `Permission: device.usb`.

## mcu.models

List supported MCU programming models.

**Returns:**
- `models` (array): Each: `{id, family, protocol, status, notes}`

## mcu.probe

Probe a connected MCU target by model.

**Params:**
- `model` (string, required): Currently `esp32`
- `name` (string, optional): USB device name from `usb.list`
- `vendor_id` / `product_id` (integer, optional): Match USB device

**Returns:**
- `device` (object): USB device descriptor snapshot
- `bridge_hint` (string|null): USB-UART bridge guess (cp210x, ch34x, ftdi)
- `serial_port` (object|null): Detected bulk IN/OUT endpoint pair
- `ready_for_serial_protocol` (boolean): True if bulk serial endpoints found

## mcu.flash

Flash an MCU image via serial protocol over USB.

**Params:**
- `model` (string, required): `esp32`
- `handle` (string, required): USB handle from `usb.open`
- `segments` (array, optional): `[{path, offset}]`. Overrides `image_path`/`offset`
- `image_path` (string, optional): Single image path (relative under user root)
- `offset` (integer, optional): Flash offset bytes. Default: 65536
- `reboot` (boolean, optional): Reboot after flash. Default: true
- `auto_enter_bootloader` (boolean, optional): Auto bootloader entry (CP210x). Default: true
- `timeout_ms` (integer, optional): Default: 2000
- `interface_id`, `in_endpoint_address`, `out_endpoint_address` (integer, optional): Explicit endpoint overrides

**Returns:**
- `segment_count` (integer), `segments` (array: `{path, offset, size, md5, blocks_written, block_size}`), `blocks_written_total`, `elapsed_ms`

## mcu.flash_plan

Build a flash segment plan from a `flasher_args.json` file. No permission required.

**Params:**
- `plan_path` (string, required): Path to flasher_args.json (relative under user root)
- `model` (string, optional): Model override. Default: inferred

**Returns:**
- `segment_count` (integer), `segments` (array: `{path, offset, exists, size}`), `missing_files` (array)

## mcu.diag_serial

Active MCU serial diagnostic -- sends sync probe.

**Params:**
- `model` (string, required): `esp32`
- `handle` (string, required): USB handle
- `auto_enter_bootloader` (boolean, optional): Default: true
- `sniff_before_ms`, `sniff_after_ms`, `timeout_ms` (integer, optional)

## mcu.serial_lines

Set MCU serial modem lines (DTR/RTS) or run preset sequence.

**Params:**
- `model` (string, required): `esp32`
- `handle` (string, required): USB handle
- `sequence` (string, optional): `enter_bootloader`, `enter_bootloader_inverted`, `run`, `none`
- `dtr`, `rts` (boolean, optional): Line states
- `sleep_after_ms`, `timeout_ms` (integer, optional)

## mcu.reset

Reset MCU target to run or bootloader mode.

**Params:**
- `model` (string, required): `esp32`
- `handle` (string, required): USB handle
- `mode` (string, optional): `reboot` (default), `bootloader`, `bootloader_inverted`, `run`
- `sleep_after_ms`, `timeout_ms` (integer, optional)

## mcu.serial_monitor

Passive MCU serial monitor (read-only, no probes).

**Params:**
- `model` (string, required): `esp32`
- `handle` (string, required): USB handle
- `duration_ms` (integer, optional): Capture duration
- `configure_serial` (boolean, optional): Default: true
- `flush_input` (boolean, optional): Default: false
- `max_dump_bytes` (integer, optional): Max: 262144
- `timeout_ms` (integer, optional)

## MicroPython actions

The following actions share common serial params:
- `model` (string, optional): `esp32` or `micropython`. Default: esp32
- `serial_handle` (string, optional): Reuse existing session from `serial.open`
- `handle` (string, optional): USB handle from `usb.open` (creates ephemeral session)
- `port_index` (integer, optional): Default: 0
- `baud_rate` (integer, optional): Default: 115200
- `timeout_ms`, `write_timeout_ms` (integer, optional)

**Notes:** Avoid mixing `handle` and `serial_handle` for the same session. For multi-step workflows, open serial once and reuse `serial_handle`. Determine whether MicroPython is present from serial or REPL responses, not from board family alone. The `model` field mainly selects transport helpers and reset behavior; it does not decide whether a board can run MicroPython.

### mcu.micropython_exec

Execute Python in MicroPython raw REPL over USB serial.

**Params (+ common serial params):**
- `code` (string, optional): UTF-8 Python source
- `code_b64` (string, optional): Base64-encoded Python source
- `settle_ms` (integer, optional): Default: 80

### mcu.micropython_write_file

Upload a file to MicroPython filesystem over raw REPL.

**Params (+ common serial params):**
- `path` (string, required): Destination path on target (e.g. `main.py`)
- `content` (string, optional): UTF-8 content
- `content_b64` (string, optional): Base64 content (binary-safe)
- `make_dirs` (boolean, optional): Default: true
- `chunk_size` (integer, optional): 64-2048. Default: 768

### mcu.micropython_soft_reset

Send soft reset (Ctrl-C/Ctrl-D) and capture boot output as a `lines` array.

**Params (+ common serial params):**
- `settle_ms` (integer, optional): Default: 0 (must be 0 to avoid FTDI buffer overflow)
- `capture_timeout_ms` (integer, optional): Total capture budget. Default: 15000
- `idle_timeout_ms` (integer, optional): Idle cutoff (ms since last byte). Default: 3000
- `max_lines` (integer, optional): Max lines to capture. Default: 200
- `drain_before_reset` (boolean, optional): Default: true
- `raw_output` (boolean, optional): Include `output_b64`/`output_ascii` fields. Default: false

**Returns:**
- `lines` (array of string): captured output lines (newlines stripped). Check for `Traceback`, `ImportError`, `SyntaxError` etc.
- `boot_complete` (boolean): true if REPL prompt `>>> ` appeared after "soft reboot". **If false, boot.py or main.py crashed/hung** — the written code likely has an error even if no Traceback is visible in `lines`.
- `line_count`, `bytes_read`, `elapsed_ms`, `truncated`, `truncation_reason`

**Notes:** Uses line-oriented capture with 100ms polling to avoid USB bulk boundary false-idle. Always check both `boot_complete` and `lines` for errors before reporting success. If `boot_complete` is false and no Traceback is visible, the boot process hung (common cause: import of a missing module in main.py).

## MicroPython troubleshooting

- Start from evidence: can USB open, can serial open, and does the device produce a REPL prompt or MicroPython banner.
- A board with an unknown or generic USB bridge name can still be a valid MicroPython target if serial and REPL work.
- `Espressif / USB JTAG/serial debug unit` is a device-specific hint. On some M5Stack boards it often means UIFlow2 / MicroPython is not installed yet, so ask the user to flash UIFlow2 or another MicroPython image with M5Burner before retrying.
- CH340, CP210x, FTDI, and other USB-UART bridges can front working MicroPython boards even when the board identity is not directly discoverable.
