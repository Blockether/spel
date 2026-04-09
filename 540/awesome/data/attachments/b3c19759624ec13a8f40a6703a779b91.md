## GET http://localhost:41509/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 09 Apr 2026 19:01:58 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41509/health' \
  -H 'Authorization: Bearer test-token'
```
