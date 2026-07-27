## GET http://localhost:45453/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 27 Jul 2026 15:29:50 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:45453/health' \
  -H 'Authorization: Bearer test-token'
```
