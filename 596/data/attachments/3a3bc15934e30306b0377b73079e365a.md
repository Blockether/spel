## GET http://localhost:41505/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 12 Apr 2026 23:04:31 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41505/health' \
  -H 'Authorization: Bearer test-token'
```
