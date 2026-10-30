## GET http://localhost:42499/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 30 Oct 2026 16:41:27 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42499/health' \
  -H 'Authorization: Bearer test-token'
```
