## GET http://localhost:46157/echo?user=alice&action=view → 200 OK

### Response Headers
```
content-length: 64
content-type: application/json
date: Sun, 22 Nov 2026 12:25:24 GMT
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
curl 'http://localhost:46157/echo?user=alice&action=view'
```
