## POST http://127.0.0.1:37943/spel/result → 200 OK

### Timing
Request started: 2026-08-24T08:25:53.085Z

### Request Headers
```
accept: */*
accept-encoding: gzip, deflate, br, zstd
connection: keep-alive
content-length: 123
content-type: application/json
host: 127.0.0.1:37943
origin: http://127.0.0.1:37943
referer: http://127.0.0.1:37943/
sec-ch-ua: "HeadlessChrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"
sec-ch-ua-mobile: ?0
sec-ch-ua-platform: "Linux"
sec-fetch-dest: empty
sec-fetch-mode: cors
sec-fetch-site: same-origin
user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.7827.55 Safari/537.36
```

### Request Body
```json
{
  "action": "no-such-action",
  "ok": false,
  "error": "unknown action: no-such-action",
  "id": "cc8bacf5-2335-40f2-b506-b191f0b43994"
}
```

### Response Headers
```
access-control-allow-headers: Content-Type
access-control-allow-methods: GET, POST, OPTIONS
access-control-allow-origin: *
access-control-allow-private-network: true
content-length: 11
content-type: application/json
date: Mon, 24 Aug 2026 08:25:53 GMT
```

### Response Body
```json
{
  "ok": true
}
```

### cURL
```bash
curl 'http://127.0.0.1:37943/spel/result' \
  -X POST \
  -H 'accept: */*' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'connection: keep-alive' \
  -H 'content-length: 123' \
  -H 'content-type: application/json' \
  -H 'host: 127.0.0.1:37943' \
  -H 'origin: http://127.0.0.1:37943' \
  -H 'referer: http://127.0.0.1:37943/' \
  -H 'sec-ch-ua: "HeadlessChrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: empty' \
  -H 'sec-fetch-mode: cors' \
  -H 'sec-fetch-site: same-origin' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.7827.55 Safari/537.36' \
  -d '{
  "action": "no-such-action",
  "ok": false,
  "error": "unknown action: no-such-action",
  "id": "cc8bacf5-2335-40f2-b506-b191f0b43994"
}'
```
