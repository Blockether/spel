## GET http://localhost:40965/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 12 Apr 2026 19:07:33 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40965/health' \
  -H 'Authorization: Bearer test-token'
```
