## GET http://localhost:38613/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 11 Nov 2026 20:01:43 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38613/health' \
  -H 'Authorization: Bearer test-token'
```
