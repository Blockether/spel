## GET http://localhost:44771/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 03 Nov 2026 16:28:06 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44771/health' \
  -H 'Authorization: Bearer test-token'
```
