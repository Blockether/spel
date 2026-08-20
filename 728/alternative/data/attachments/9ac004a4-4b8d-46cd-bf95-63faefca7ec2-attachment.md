## GET https://example.org/ → 200 

### Timing
Request started: 2026-08-20T10:21:05.201Z

### Request Headers
```
:authority: example.org
:method: GET
:path: /
:scheme: https
accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7
accept-encoding: gzip, deflate, br, zstd
priority: u=0, i
sec-ch-ua: "HeadlessChrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"
sec-ch-ua-mobile: ?0
sec-ch-ua-platform: "Linux"
sec-fetch-dest: document
sec-fetch-mode: navigate
sec-fetch-site: none
sec-fetch-user: ?1
upgrade-insecure-requests: 1
user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.7827.55 Safari/537.36
```

### Response Headers
```
age: 6960
allow: GET, HEAD
cache-control: max-age=14400
cf-cache-status: HIT
cf-ray: a2e0ab4bd86c55c2-SJC
content-encoding: br
content-type: text/html
date: Thu, 20 Aug 2026 10:21:05 GMT
last-modified: Wed, 12 Aug 2026 20:17:18 GMT
server: cloudflare
```

### Response Body
```html
<!doctype html><html lang="en"><head><title>Example Domain</title><link rel="icon" href="data:,"><meta name="viewport" content="width=device-width, initial-scale=1"><style>body{background:#eee;width:60vw;margin:15vh auto;font-family:system-ui,sans-serif}h1{font-size:1.5em}div{opacity:0.8}a:link,a:visited{color:#348}</style></head><body><div><h1>Example Domain</h1><p>This domain is for use in documentation examples without needing permission. Avoid use in operations.</p><p><a href="https://iana.org/domains/example">Learn more</a></p></div></body></html>

```

### cURL
```bash
curl 'https://example.org/' \
  -H ':authority: example.org' \
  -H ':method: GET' \
  -H ':path: /' \
  -H ':scheme: https' \
  -H 'accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'priority: u=0, i' \
  -H 'sec-ch-ua: "HeadlessChrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: document' \
  -H 'sec-fetch-mode: navigate' \
  -H 'sec-fetch-site: none' \
  -H 'sec-fetch-user: ?1' \
  -H 'upgrade-insecure-requests: 1' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.7827.55 Safari/537.36'
```
