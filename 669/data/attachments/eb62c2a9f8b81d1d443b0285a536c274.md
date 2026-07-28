## GET http://localhost:39291/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 28 Jul 2026 15:07:26 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39291/health' \
  -H 'Authorization: Bearer test-token'
```
