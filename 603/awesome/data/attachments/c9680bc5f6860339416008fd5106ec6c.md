## GET http://localhost:46085/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 13 Apr 2026 13:00:50 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46085/health' \
  -H 'Authorization: Bearer test-token'
```
