## GET http://localhost:42743/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 16 Aug 2026 14:49:53 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42743/health' \
  -H 'Authorization: Bearer test-token'
```
