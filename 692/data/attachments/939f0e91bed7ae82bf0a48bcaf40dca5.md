## GET http://localhost:35183/echo?user=alice&action=view&limit=50 → 200 OK

### Response Headers
```
content-length: 73
content-type: application/json
date: Sun, 09 Aug 2026 20:00:49 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "user=alice&action=view&limit=50"
}
```

### cURL
```bash
curl 'http://localhost:35183/echo?user=alice&action=view&limit=50'
```
