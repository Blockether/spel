## GET http://localhost:39847/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Mon, 13 Apr 2026 08:31:59 GMT
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
curl 'http://localhost:39847/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
