## GET http://localhost:46615/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 28 Nov 2026 11:57:16 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46615/health' \
  -H 'Authorization: Bearer test-token'
```
