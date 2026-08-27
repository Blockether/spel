## GET http://localhost:42313/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 27 Aug 2026 17:09:01 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42313/health' \
  -H 'Authorization: Bearer test-token'
```
