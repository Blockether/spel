## GET http://127.0.0.1:40131/main.js?version=1 → 200 OK

### Timing
Request started: 2026-10-30T12:27:09.200Z

### Request Headers
```
accept: */*
accept-encoding: gzip, deflate, br, zstd
connection: keep-alive
host: 127.0.0.1:40131
referer: http://127.0.0.1:40131/
sec-ch-ua: "HeadlessChrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"
sec-ch-ua-mobile: ?0
sec-ch-ua-platform: "Linux"
sec-fetch-dest: empty
sec-fetch-mode: cors
sec-fetch-site: same-origin
user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.7827.55 Safari/537.36
```

### Response Headers
```
content-length: 178
content-type: application/javascript
date: Fri, 30 Oct 2026 12:27:09 GMT
```

### Response Body
```
window.makeError = function(){return new Error('mapped');}; window.savedError = makeError(); setTimeout(function(){throw window.savedError;}, 0);
//# sourceMappingURL=main.js.map
```

### cURL
```bash
curl 'http://127.0.0.1:40131/main.js?version=1' \
  -H 'accept: */*' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'connection: keep-alive' \
  -H 'host: 127.0.0.1:40131' \
  -H 'referer: http://127.0.0.1:40131/' \
  -H 'sec-ch-ua: "HeadlessChrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: empty' \
  -H 'sec-fetch-mode: cors' \
  -H 'sec-fetch-site: same-origin' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.7827.55 Safari/537.36'
```
