## GET http://localhost:43933/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 21 Nov 2026 18:56:53 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43933/health' \
  -H 'Authorization: Bearer test-token'
```
