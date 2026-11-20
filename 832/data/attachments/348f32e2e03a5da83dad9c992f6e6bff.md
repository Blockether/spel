## GET http://localhost:45401/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Fri, 20 Nov 2026 07:44:17 GMT
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
curl 'http://localhost:45401/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
