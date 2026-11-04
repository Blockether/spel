## GET http://127.0.0.1:44691/ → 200 OK

### Timing
Request started: 2026-11-04T10:58:15.028Z

### Request Headers
```
accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7
accept-encoding: gzip, deflate, br, zstd
accept-language: en-US,en;q=0.9
connection: keep-alive
host: 127.0.0.1:44691
referer: http://127.0.0.1:44691/
sec-ch-ua: "Not:A-Brand";v="8", "Chromium";v="156"
sec-ch-ua-mobile: ?0
sec-ch-ua-platform: "Linux"
sec-fetch-dest: iframe
sec-fetch-mode: navigate
sec-fetch-site: same-origin
sec-fetch-user: ?1
upgrade-insecure-requests: 1
user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.0.0 Safari/537.36
```

### Response Headers
```
content-length: 57
content-type: text/html
date: Wed, 04 Nov 2026 10:58:15 GMT
```

### Response Body
```html
<!doctype html><script src='/main.js?version=1'></script>
```

### cURL
```bash
curl 'http://127.0.0.1:44691/' \
  -H 'accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'accept-language: en-US,en;q=0.9' \
  -H 'connection: keep-alive' \
  -H 'host: 127.0.0.1:44691' \
  -H 'referer: http://127.0.0.1:44691/' \
  -H 'sec-ch-ua: "Not:A-Brand";v="8", "Chromium";v="156"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: iframe' \
  -H 'sec-fetch-mode: navigate' \
  -H 'sec-fetch-site: same-origin' \
  -H 'sec-fetch-user: ?1' \
  -H 'upgrade-insecure-requests: 1' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.0.0 Safari/537.36'
```
