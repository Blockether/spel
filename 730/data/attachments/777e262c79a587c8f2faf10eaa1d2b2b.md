## GET http://localhost:42361/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 20 Aug 2026 12:40:08 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42361/health' \
  -H 'Authorization: Bearer test-token'
```
