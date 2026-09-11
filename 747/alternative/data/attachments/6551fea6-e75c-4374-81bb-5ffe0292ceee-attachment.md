## GET http://localhost:44341/echo?user=alice&action=view&limit=50 → 200 OK

### Response Headers
```
content-length: 73
content-type: application/json
date: Fri, 11 Sep 2026 15:01:05 GMT
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
curl 'http://localhost:44341/echo?user=alice&action=view&limit=50'
```
