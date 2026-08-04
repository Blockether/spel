## GET http://localhost:43307/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 04 Aug 2026 18:30:23 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43307/health' \
  -H 'Authorization: Bearer test-token'
```
