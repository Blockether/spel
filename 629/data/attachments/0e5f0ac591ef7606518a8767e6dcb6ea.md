## GET http://localhost:36933/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 08 Jul 2026 16:44:33 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36933/health' \
  -H 'Authorization: Bearer test-token'
```
