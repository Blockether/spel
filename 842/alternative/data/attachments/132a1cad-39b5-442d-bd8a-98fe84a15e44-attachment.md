## GET http://localhost:43577/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Sun, 22 Nov 2026 01:23:02 GMT
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
curl 'http://localhost:43577/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
