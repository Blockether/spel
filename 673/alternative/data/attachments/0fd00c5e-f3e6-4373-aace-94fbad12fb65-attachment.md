## GET http://localhost:42975/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 01 Aug 2026 11:40:07 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42975/health' \
  -H 'Authorization: Bearer test-token'
```
