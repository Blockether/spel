## GET http://localhost:37649/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Thu, 29 Oct 2026 14:01:46 GMT
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
curl 'http://localhost:37649/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
