## GET http://localhost:46615/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Sat, 28 Nov 2026 11:57:17 GMT
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
curl 'http://localhost:46615/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
