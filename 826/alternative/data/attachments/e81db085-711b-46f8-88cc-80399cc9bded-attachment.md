## GET http://localhost:43791/health?i=208 → 200 OK

### Timing
Request started: 2026-11-04T12:13:00.999Z

### Request Headers
```
accept: */*
accept-encoding: gzip, deflate, br, zstd
connection: keep-alive
host: localhost:43791
referer: http://localhost:43791/health
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
date: Wed, 04 Nov 2026 12:13:00 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43791/health?i=208' \
  -H 'accept: */*' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'connection: keep-alive' \
  -H 'host: localhost:43791' \
  -H 'referer: http://localhost:43791/health' \
  -H 'sec-ch-ua: "Not:A-Brand";v="8", "Chromium";v="156", "HeadlessChrome";v="156"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: empty' \
  -H 'sec-fetch-mode: cors' \
  -H 'sec-fetch-site: same-origin' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.8078.4 Safari/537.36'
```
