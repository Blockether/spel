## GET http://localhost:37219/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Sun, 16 Aug 2026 02:40:17 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "id=1"
}
```

### cURL
```bash
curl 'http://localhost:37219/echo?id=1'
```
