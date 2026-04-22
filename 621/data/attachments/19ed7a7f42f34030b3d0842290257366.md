## GET http://localhost:42051/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Wed, 22 Apr 2026 17:51:23 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "page=1&limit=10"
}
```

### cURL
```bash
curl 'http://localhost:42051/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
