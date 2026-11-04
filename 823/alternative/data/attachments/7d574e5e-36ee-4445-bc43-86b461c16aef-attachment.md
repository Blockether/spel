## GET http://localhost:38169/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 04 Nov 2026 10:52:18 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38169/health' \
  -H 'Authorization: Bearer test-token'
```
