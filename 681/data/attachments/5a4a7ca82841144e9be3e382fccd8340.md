## GET http://localhost:33263/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 04 Aug 2026 13:22:27 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33263/health' \
  -H 'Authorization: Bearer test-token'
```
