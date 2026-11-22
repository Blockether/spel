## GET https://example.org/ → 200 

### Timing
Request started: 2026-11-22T19:50:55.842Z

### Request Headers
```
sec-ch-ua: "Not:A-Brand";v="8", "Chromium";v="156", "HeadlessChrome";v="156"
sec-ch-ua-mobile: ?0
sec-ch-ua-platform: "Linux"
upgrade-insecure-requests: 1
user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.8078.4 Safari/537.36
```

### Response Headers
```
age: 1792
allow: GET, HEAD
alt-svc: h3=":443"; ma=86400
cache-control: max-age=14400
cf-cache-status: HIT
cf-ray: a5ea77438a37e6e0-PDX
content-encoding: br
content-type: text/html; charset=utf-8
date: Sun, 22 Nov 2026 19:50:55 GMT
last-modified: Thu, 19 Nov 2026 22:10:26 GMT
server: cloudflare
```

### cURL
```bash
curl 'https://example.org/' \
  -H 'sec-ch-ua: "Not:A-Brand";v="8", "Chromium";v="156", "HeadlessChrome";v="156"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'upgrade-insecure-requests: 1' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.8078.4 Safari/537.36'
```
