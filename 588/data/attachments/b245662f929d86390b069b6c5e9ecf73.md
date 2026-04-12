## GET http://localhost:42281/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Sun, 12 Apr 2026 15:49:00 GMT
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
curl 'http://localhost:42281/echo?id=1'
```
