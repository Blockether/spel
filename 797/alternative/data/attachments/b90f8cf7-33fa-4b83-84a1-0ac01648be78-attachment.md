## GET http://localhost:34333/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 24 Oct 2026 08:08:29 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:34333/health' \
  -H 'Authorization: Bearer test-token'
```
