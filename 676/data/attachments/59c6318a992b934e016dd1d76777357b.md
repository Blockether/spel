## GET http://localhost:33517/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 01 Aug 2026 16:26:54 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33517/health' \
  -H 'Authorization: Bearer test-token'
```
