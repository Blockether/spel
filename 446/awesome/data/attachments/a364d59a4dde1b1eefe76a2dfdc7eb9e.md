## GET /health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 10 Mar 2026 08:50:44 GMT
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
