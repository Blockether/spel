## GET /health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 03 Mar 2026 05:10:17 GMT
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
