## GET http://localhost:38711/echo?user=alice&action=view&limit=50 → 200 OK

### Request Headers
```
GET http://localhost:38711/echo?user=alice&action=view&limit=50
```

### Response Headers
```
content-length: 73
content-type: application/json
date: Wed, 18 Mar 2026 16:44:16 GMT
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
curl 'http://localhost:38711/echo?user=alice&action=view&limit=50'
```
