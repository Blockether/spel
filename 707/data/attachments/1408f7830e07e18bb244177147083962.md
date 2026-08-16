## GET http://localhost:44811/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 16 Aug 2026 15:47:44 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44811/health' \
  -H 'Authorization: Bearer test-token'
```
