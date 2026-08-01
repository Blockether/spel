## GET http://localhost:35869/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 01 Aug 2026 08:57:31 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35869/health' \
  -H 'Authorization: Bearer test-token'
```
