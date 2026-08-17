## GET http://localhost:46535/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 17 Aug 2026 19:38:47 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46535/health' \
  -H 'Authorization: Bearer test-token'
```
