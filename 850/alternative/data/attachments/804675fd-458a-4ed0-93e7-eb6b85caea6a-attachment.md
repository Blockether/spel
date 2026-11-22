## GET http://localhost:36921/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 22 Nov 2026 15:42:49 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36921/health' \
  -H 'Authorization: Bearer test-token'
```
