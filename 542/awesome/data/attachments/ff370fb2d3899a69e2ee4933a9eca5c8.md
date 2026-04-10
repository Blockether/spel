## GET http://localhost:39179/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 10 Apr 2026 03:53:04 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39179/health' \
  -H 'Authorization: Bearer test-token'
```
