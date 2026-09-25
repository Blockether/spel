## GET http://localhost:37829/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 24 Sep 2026 23:50:48 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:37829/health' \
  -H 'Authorization: Bearer test-token'
```
