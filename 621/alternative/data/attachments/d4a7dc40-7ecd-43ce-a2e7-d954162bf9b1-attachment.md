## GET http://localhost:42051/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 22 Apr 2026 17:51:23 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42051/health' \
  -H 'Authorization: Bearer test-token'
```
