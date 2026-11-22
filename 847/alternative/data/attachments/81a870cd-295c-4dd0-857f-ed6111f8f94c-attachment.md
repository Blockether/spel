## GET http://localhost:38523/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Sun, 22 Nov 2026 13:33:17 GMT
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
curl 'http://localhost:38523/echo?id=1'
```
