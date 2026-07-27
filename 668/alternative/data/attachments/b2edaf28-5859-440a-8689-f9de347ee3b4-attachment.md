## GET http://localhost:34101/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 27 Jul 2026 21:19:48 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:34101/health' \
  -H 'Authorization: Bearer test-token'
```
