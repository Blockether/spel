## GET http://localhost:43165/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 21 Aug 2026 02:16:08 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43165/health' \
  -H 'Authorization: Bearer test-token'
```
