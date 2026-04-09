## GET http://localhost:33191/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 09 Apr 2026 18:15:17 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33191/health' \
  -H 'Authorization: Bearer test-token'
```
