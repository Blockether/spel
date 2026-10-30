## GET http://127.0.0.1:34063/main.js?version=1 → 200 OK

### Timing
Request started: 2026-10-30T16:45:45.018Z

### Request Headers
```
accept: */*
accept-encoding: gzip, deflate, br, zstd
accept-language: en-US,en;q=0.9
connection: keep-alive
host: 127.0.0.1:34063
referer: http://127.0.0.1:34063/
sec-ch-ua: "Chromium";v="149", "Not)A;Brand";v="24"
sec-ch-ua-mobile: ?0
sec-ch-ua-platform: "Linux"
sec-fetch-dest: empty
sec-fetch-mode: cors
sec-fetch-site: same-origin
user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.0.0 Safari/537.36
```

### Response Headers
```
content-length: 178
content-type: application/javascript
date: Fri, 30 Oct 2026 16:45:45 GMT
```

### Response Body
```
window.makeError = function(){return new Error('mapped');}; window.savedError = makeError(); setTimeout(function(){throw window.savedError;}, 0);
//# sourceMappingURL=main.js.map
```

### cURL
```bash
curl 'http://127.0.0.1:34063/main.js?version=1' \
  -H 'accept: */*' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'accept-language: en-US,en;q=0.9' \
  -H 'connection: keep-alive' \
  -H 'host: 127.0.0.1:34063' \
  -H 'referer: http://127.0.0.1:34063/' \
  -H 'sec-ch-ua: "Chromium";v="149", "Not)A;Brand";v="24"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: empty' \
  -H 'sec-fetch-mode: cors' \
  -H 'sec-fetch-site: same-origin' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.0.0 Safari/537.36'
```
