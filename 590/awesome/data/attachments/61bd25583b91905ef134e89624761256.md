## GET http://localhost:34673/echo?user=alice&action=view → 200 OK

### Response Headers
```
content-length: 64
content-type: application/json
date: Sun, 12 Apr 2026 18:55:08 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "user=alice&action=view"
}
```

### cURL
```bash
curl 'http://localhost:34673/echo?user=alice&action=view'
```
