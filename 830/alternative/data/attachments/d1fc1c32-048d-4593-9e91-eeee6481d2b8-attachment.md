## GET https://example.org/ → 200 

### Timing
Request started: 2026-11-19T12:54:42.281Z

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
age: 8752
allow: GET, HEAD
alt-svc: h3=":443"; ma=86400
cache-control: max-age=14400
cf-cache-status: HIT
cf-ray: a5cf5d6d6dc60628-IAD
content-encoding: br
content-type: text/html; charset=utf-8
date: Thu, 19 Nov 2026 12:54:41 GMT
last-modified: Wed, 18 Nov 2026 10:54:07 GMT
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
