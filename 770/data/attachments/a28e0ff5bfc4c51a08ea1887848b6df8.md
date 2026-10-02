## GET http://localhost:34799/echo?id=1&fields=name%2Cemail → 200 OK

### Response Headers
```
content-length: 64
content-type: application/json
date: Fri, 02 Oct 2026 20:24:05 GMT
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
curl 'http://localhost:34799/echo?id=1&fields=name%2Cemail'
```
