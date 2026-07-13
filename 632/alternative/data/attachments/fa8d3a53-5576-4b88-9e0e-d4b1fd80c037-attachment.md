## GET http://localhost:39245/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 13 Jul 2026 11:57:06 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39245/health' \
  -H 'Authorization: Bearer test-token'
```
