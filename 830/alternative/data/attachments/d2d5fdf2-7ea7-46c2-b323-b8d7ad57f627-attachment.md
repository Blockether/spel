## GET http://localhost:40711/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 19 Nov 2026 12:49:05 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40711/health' \
  -H 'Authorization: Bearer test-token'
```
