## GET http://localhost:42315/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 11 Apr 2026 11:44:42 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42315/health' \
  -H 'Authorization: Bearer test-token'
```
