## GET http://localhost:35183/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Sun, 09 Aug 2026 20:00:49 GMT
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
curl 'http://localhost:35183/echo?id=1'
```
