## GET http://localhost:44071/health?i=319 → 200 OK

### Timing
Request started: 2026-11-04T10:55:50.586Z

### Request Headers
```
accept: */*
accept-encoding: gzip, deflate, br, zstd
connection: keep-alive
host: localhost:44071
referer: http://localhost:44071/health
sec-ch-ua: "Not:A-Brand";v="8", "Chromium";v="156", "HeadlessChrome";v="156"
sec-ch-ua-mobile: ?0
sec-ch-ua-platform: "Linux"
sec-fetch-dest: empty
sec-fetch-mode: cors
sec-fetch-site: same-origin
user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.8078.4 Safari/537.36
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 04 Nov 2026 10:55:50 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44071/health?i=319' \
  -H 'accept: */*' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'connection: keep-alive' \
  -H 'host: localhost:44071' \
  -H 'referer: http://localhost:44071/health' \
  -H 'sec-ch-ua: "Not:A-Brand";v="8", "Chromium";v="156", "HeadlessChrome";v="156"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: empty' \
  -H 'sec-fetch-mode: cors' \
  -H 'sec-fetch-site: same-origin' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.8078.4 Safari/537.36'
```
