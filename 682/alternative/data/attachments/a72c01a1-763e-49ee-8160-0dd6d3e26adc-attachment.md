## GET http://localhost:38981/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 04 Aug 2026 14:05:05 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38981/health' \
  -H 'Authorization: Bearer test-token'
```
