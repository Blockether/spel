## GET http://localhost:39803/echo?user=alice&action=view&limit=50 → 200 OK

### Response Headers
```
content-length: 73
content-type: application/json
date: Fri, 11 Sep 2026 11:38:08 GMT
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
curl 'http://localhost:39803/echo?user=alice&action=view&limit=50'
```
