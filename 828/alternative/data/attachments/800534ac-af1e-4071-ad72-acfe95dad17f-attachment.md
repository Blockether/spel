## GET http://localhost:35365/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 11 Nov 2026 22:40:07 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35365/health' \
  -H 'Authorization: Bearer test-token'
```
