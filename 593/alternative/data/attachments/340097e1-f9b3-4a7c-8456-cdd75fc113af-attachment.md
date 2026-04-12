## GET http://localhost:34931/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Sun, 12 Apr 2026 19:26:29 GMT
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
curl 'http://localhost:34931/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
