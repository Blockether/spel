## GET http://localhost:43871/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 04 Aug 2026 20:33:47 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43871/health' \
  -H 'Authorization: Bearer test-token'
```
