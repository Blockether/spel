## GET http://localhost:39993/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 10 Apr 2026 22:29:01 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39993/health' \
  -H 'Authorization: Bearer test-token'
```
