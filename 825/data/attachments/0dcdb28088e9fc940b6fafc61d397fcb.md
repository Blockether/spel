## GET http://localhost:42591/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Wed, 04 Nov 2026 12:02:40 GMT
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
curl 'http://localhost:42591/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
