## GET http://localhost:39733/echo?user=alice&action=view&limit=50 → 200 OK

### Request Headers
```
GET http://localhost:39733/echo?user=alice&action=view&limit=50
```

### Response Headers
```
content-length: 73
content-type: application/json
date: Fri, 10 Apr 2026 08:22:06 GMT
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
curl 'http://localhost:39733/echo?user=alice&action=view&limit=50'
```
