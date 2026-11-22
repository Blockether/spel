## GET http://localhost:41193/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 22 Nov 2026 16:06:47 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41193/health' \
  -H 'Authorization: Bearer test-token'
```
