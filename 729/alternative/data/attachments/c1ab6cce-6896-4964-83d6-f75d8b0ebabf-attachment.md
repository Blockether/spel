## POST http://127.0.0.1:42753/spel/result?t=tkn → 200 OK

### Timing
Request started: 2026-08-20T11:03:40.646Z

### Request Headers
```
accept: */*
accept-encoding: gzip, deflate, br, zstd
connection: keep-alive
content-length: 86
content-type: application/json
host: 127.0.0.1:42753
origin: http://127.0.0.1:42753
referer: http://127.0.0.1:42753/
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
  "action": "ping",
  "ok": true,
  "value": "pong",
  "id": "c67af5d0-36fb-4203-9489-e9f5b4ae77ab"
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
date: Thu, 20 Aug 2026 11:03:40 GMT
```

### Response Body
```json
{
  "ok": true
}
```

### cURL
```bash
curl 'http://127.0.0.1:42753/spel/result?t=tkn' \
  -X POST \
  -H 'accept: */*' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'connection: keep-alive' \
  -H 'content-length: 86' \
  -H 'content-type: application/json' \
  -H 'host: 127.0.0.1:42753' \
  -H 'origin: http://127.0.0.1:42753' \
  -H 'referer: http://127.0.0.1:42753/' \
  -H 'sec-ch-ua: "HeadlessChrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: empty' \
  -H 'sec-fetch-mode: cors' \
  -H 'sec-fetch-site: same-origin' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.7827.55 Safari/537.36' \
  -d '{
  "action": "ping",
  "ok": true,
  "value": "pong",
  "id": "c67af5d0-36fb-4203-9489-e9f5b4ae77ab"
}'
```
