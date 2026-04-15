## GET http://localhost:44455/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 15 Apr 2026 10:40:03 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44455/health' \
  -H 'Authorization: Bearer test-token'
```
