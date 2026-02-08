# USB Isochronous Transfer Support (Native Workaround)

Android's public USB Host APIs do not support isochronous endpoints directly.
methings includes a native workaround that uses the `UsbDeviceConnection` file descriptor
and Linux `usbfs` URB ioctls to perform an isochronous IN transfer.

## API

Local HTTP endpoint (Kotlin control plane):

- `POST /usb/iso_transfer`

Request JSON:

- `handle`: string (from `/usb/open`)
- `endpoint_address`: int (e.g. `0x81`)
- `interface_id`: int (optional, but recommended)
- `alt_setting`: int (optional; if omitted we pick the first interface+alt that contains the endpoint)
- `packet_size`: int (bytes per isoch packet)
- `num_packets`: int (packets per URB)
- `timeout_ms`: int
- `force`: bool (claimInterface force; default true)

Response JSON:

- `status: "ok"`
- `data_b64`: concatenated payload bytes from all packets with `actual_length > 0`
- `packets`: array of `{status, actual_length}` per packet

Python tool access:

- `device_api` action `usb.iso_transfer` maps to `/usb/iso_transfer`.

## Notes

- This is a low-level primitive. Higher-level UVC streaming requires parsing UVC payload headers,
  handling frame boundaries, and selecting the correct VideoStreaming alternate setting.
- The transfer is performed entirely inside the app process; only the resulting bytes are returned
  to the Python/agent side via HTTP.
