## GET http://localhost:37117/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 17 Jul 2026 19:39:15 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:37117/health' \
  -H 'Authorization: Bearer test-token'
```
