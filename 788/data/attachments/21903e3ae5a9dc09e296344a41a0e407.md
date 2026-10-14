## GET http://localhost:44533/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 14 Oct 2026 12:41:25 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44533/health' \
  -H 'Authorization: Bearer test-token'
```
