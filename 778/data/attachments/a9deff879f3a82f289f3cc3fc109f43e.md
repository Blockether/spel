## GET http://localhost:43173/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 03 Oct 2026 17:10:29 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43173/health' \
  -H 'Authorization: Bearer test-token'
```
