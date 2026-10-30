## GET http://127.0.0.1:33789/main.js.map → 200 OK

### Timing
Request started: 2026-10-30T15:37:58.490Z

### Request Headers
```
accept: */*
accept-encoding: gzip, deflate, br, zstd
accept-language: en-US,en;q=0.9
connection: keep-alive
host: 127.0.0.1:33789
referer: http://127.0.0.1:33789/
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
content-length: 136
content-type: application/json
date: Fri, 30 Oct 2026 15:37:58 GMT
```

### Response Body
```json
{
  "version": 3,
  "sections": [
    {
      "offset": {
        "line": 0,
        "column": 0
      },
      "map": {
        "version": 3,
        "sources": [
          "glms/app.cljs"
        ],
        "names": [
        ],
        "mappings": "AASA"
      }
    }
  ]
}
```

### cURL
```bash
curl 'http://127.0.0.1:33789/main.js.map' \
  -H 'accept: */*' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'accept-language: en-US,en;q=0.9' \
  -H 'connection: keep-alive' \
  -H 'host: 127.0.0.1:33789' \
  -H 'referer: http://127.0.0.1:33789/' \
  -H 'sec-ch-ua: "Chromium";v="149", "Not)A;Brand";v="24"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: empty' \
  -H 'sec-fetch-mode: cors' \
  -H 'sec-fetch-site: same-origin' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.0.0 Safari/537.36'
```
