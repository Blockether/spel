## GET http://localhost:36521/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 03 Nov 2026 22:23:56 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36521/health' \
  -H 'Authorization: Bearer test-token'
```
