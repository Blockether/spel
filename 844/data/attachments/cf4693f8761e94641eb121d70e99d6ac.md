## GET http://localhost:45429/echo?id=1&fields=name%2Cemail → 200 OK

### Response Headers
```
content-length: 64
content-type: application/json
date: Sun, 22 Nov 2026 12:25:22 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "id=1&fields=name,email"
}
```

### cURL
```bash
curl 'http://localhost:45429/echo?id=1&fields=name%2Cemail'
```
