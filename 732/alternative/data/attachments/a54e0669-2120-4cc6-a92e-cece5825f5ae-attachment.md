## GET http://localhost:36829/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Fri, 21 Aug 2026 03:01:49 GMT
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
curl 'http://localhost:36829/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
