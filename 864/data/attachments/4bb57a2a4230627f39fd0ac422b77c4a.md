## GET http://localhost:40385/echo?id=1&fields=name%2Cemail → 200 OK

### Response Headers
```
content-length: 64
content-type: application/json
date: Mon, 23 Nov 2026 20:40:52 GMT
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
curl 'http://localhost:40385/echo?id=1&fields=name%2Cemail'
```
