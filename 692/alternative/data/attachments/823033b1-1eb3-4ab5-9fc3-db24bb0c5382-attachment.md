## GET http://localhost:35979/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 09 Aug 2026 20:00:46 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35979/health' \
  -H 'Authorization: Bearer test-token'
```
