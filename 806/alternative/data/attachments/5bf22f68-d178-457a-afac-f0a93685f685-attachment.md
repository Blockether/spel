## GET http://localhost:44759/echo?id=1&fields=name%2Cemail → 200 OK

### Response Headers
```
content-length: 64
content-type: application/json
date: Fri, 30 Oct 2026 11:14:32 GMT
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
curl 'http://localhost:44759/echo?id=1&fields=name%2Cemail'
```
