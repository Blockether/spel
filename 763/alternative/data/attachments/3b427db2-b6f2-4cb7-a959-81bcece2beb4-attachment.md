## GET http://localhost:44451/echo?id=1&fields=name%2Cemail → 200 OK

### Response Headers
```
content-length: 64
content-type: application/json
date: Thu, 24 Sep 2026 23:05:04 GMT
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
curl 'http://localhost:44451/echo?id=1&fields=name%2Cemail'
```
