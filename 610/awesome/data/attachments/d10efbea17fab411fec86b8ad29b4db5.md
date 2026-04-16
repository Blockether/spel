## GET http://localhost:45901/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 16 Apr 2026 11:47:10 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:45901/health' \
  -H 'Authorization: Bearer test-token'
```
