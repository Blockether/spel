## GET http://localhost:45181/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 29 Oct 2026 15:05:02 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:45181/health' \
  -H 'Authorization: Bearer test-token'
```
