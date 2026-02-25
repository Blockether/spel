## GET /health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 25 Feb 2026 10:39:23 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl '/health' \
  -H 'Authorization: Bearer test-token'
```
