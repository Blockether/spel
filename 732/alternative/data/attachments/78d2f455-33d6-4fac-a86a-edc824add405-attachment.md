## GET http://127.0.0.1:41089/spel/clients?t=dlg → 200 OK

### Timing
Request started: 2026-08-21T03:02:46.367Z

### Request Headers
```
accept: */*
accept-encoding: gzip, deflate, br, zstd
connection: keep-alive
host: 127.0.0.1:41089
referer: http://127.0.0.1:41089/
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
access-control-allow-headers: Content-Type
access-control-allow-methods: GET, POST, OPTIONS
access-control-allow-origin: *
access-control-allow-private-network: true
content-length: 327
content-type: application/json
date: Fri, 21 Aug 2026 03:02:46 GMT
```

### Response Body
```json
{
  "clients": [
    {
      "connected-at": 1787281366243,
      "transport": "sse",
      "url": "http:\/\/127.0.0.1:41089\/",
      "title": "spel bridge",
      "tab-id": "tab-mt2d6g9t-itr5v9",
      "user-agent": "Mozilla\/5.0 (X11; Linux x86_64) AppleWebKit\/537.36 (KHTML, like Gecko) HeadlessChrome\/149.0.7827.55 Safari\/537.36",
      "id": "b61a9079-6c8a-447a-bcd4-eadb370784cb"
    }
  ]
}
```

### cURL
```bash
curl 'http://127.0.0.1:41089/spel/clients?t=dlg' \
  -H 'accept: */*' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'connection: keep-alive' \
  -H 'host: 127.0.0.1:41089' \
  -H 'referer: http://127.0.0.1:41089/' \
  -H 'sec-ch-ua: "HeadlessChrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: empty' \
  -H 'sec-fetch-mode: cors' \
  -H 'sec-fetch-site: same-origin' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.7827.55 Safari/537.36'
```
