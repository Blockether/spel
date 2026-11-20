## GET http://localhost:45401/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 20 Nov 2026 07:44:17 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:45401/health' \
  -H 'Authorization: Bearer test-token'
```
