## GET http://localhost:45505/echo?page=1&limit=10 → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 57
content-type: application/json
date: Tue, 29 Sep 2026 15:44:57 GMT
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
curl 'http://localhost:45505/echo?page=1&limit=10' \
  -H 'Authorization: Bearer test-token'
```
