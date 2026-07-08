## GET http://localhost:38729/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 08 Jul 2026 11:03:55 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38729/health' \
  -H 'Authorization: Bearer test-token'
```
