## GET http://localhost:33315/echo?user=alice&action=view&limit=50 → 200 OK

### Request Headers
```
GET http://localhost:33315/echo?user=alice&action=view&limit=50
```

### Response Headers
```
content-length: 73
content-type: application/json
date: Thu, 09 Apr 2026 18:15:19 GMT
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
curl 'http://localhost:33315/echo?user=alice&action=view&limit=50'
```
