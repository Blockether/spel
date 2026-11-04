## GET https://www.iana.org/help/example-domains → 200 

### Timing
Request started: 2026-11-04T12:07:12.555Z

### Request Headers
```
:authority: www.iana.org
:method: GET
:path: /help/example-domains
:scheme: https
accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7
accept-encoding: gzip, deflate, br, zstd
priority: u=0, i
referer: https://example.org/
sec-ch-ua: "Not:A-Brand";v="8", "Chromium";v="156", "HeadlessChrome";v="156"
sec-ch-ua-mobile: ?0
sec-ch-ua-platform: "Linux"
sec-fetch-dest: document
sec-fetch-mode: navigate
sec-fetch-site: cross-site
sec-fetch-user: ?1
upgrade-insecure-requests: 1
user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.8078.4 Safari/537.36
```

### Response Headers
```
accept-ranges: bytes
age: 1996
alt-svc: h3=":443"; ma=86400
cache-control: public, max-age=3600
cf-cache-status: HIT
cf-ray: a5537f3fbcf50d77-LAX
content-encoding: br
content-length: 1790
content-security-policy: upgrade-insecure-requests; default-src 'self' https://*.iana.org; script-src 'self' 'unsafe-inline' 'unsafe-eval' https://www.google.com https://cse.google.com https://clients1.google.com; style-src 'self' 'unsafe-inline' https://www.google.com; child-src 'self' https://www.youtube.com https://clients1.google.com https://cse.google.com https://www.google.com/; img-src 'self' https://data.iana.org https://www.iana.org https://www.google.com https://www.googleapis.com https://clients1.google.com https://*.gstatic.com;
content-type: text/html; charset=utf-8
cross-origin-opener-policy: same-origin
date: Wed, 04 Nov 2026 12:07:12 GMT
expires: Wed, 04 Nov 2026 11:45:02 GMT
last-modified: Wed, 04 Nov 2026 11:33:56 GMT
referrer-policy: same-origin
server: cloudflare
strict-transport-security: max-age=48211200; preload
vary: Accept-Encoding
x-content-type-options: nosniff, nosniff
x-frame-options: DENY
```

### cURL
```bash
curl 'https://www.iana.org/help/example-domains' \
  -H ':authority: www.iana.org' \
  -H ':method: GET' \
  -H ':path: /help/example-domains' \
  -H ':scheme: https' \
  -H 'accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'priority: u=0, i' \
  -H 'referer: https://example.org/' \
  -H 'sec-ch-ua: "Not:A-Brand";v="8", "Chromium";v="156", "HeadlessChrome";v="156"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: document' \
  -H 'sec-fetch-mode: navigate' \
  -H 'sec-fetch-site: cross-site' \
  -H 'sec-fetch-user: ?1' \
  -H 'upgrade-insecure-requests: 1' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.8078.4 Safari/537.36'
```
