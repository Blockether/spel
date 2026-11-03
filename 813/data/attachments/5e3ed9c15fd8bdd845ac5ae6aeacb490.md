## GET http://localhost:37963/echo?user=alice&action=view&limit=50 → 200 OK

### Response Headers
```
content-length: 73
content-type: application/json
date: Tue, 03 Nov 2026 17:51:39 GMT
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
curl 'http://localhost:37963/echo?user=alice&action=view&limit=50'
```
