## GET http://localhost:38251/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 20 Jul 2026 09:30:46 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38251/health' \
  -H 'Authorization: Bearer test-token'
```
