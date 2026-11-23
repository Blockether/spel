## GET http://localhost:34217/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 23 Nov 2026 10:51:56 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:34217/health' \
  -H 'Authorization: Bearer test-token'
```
