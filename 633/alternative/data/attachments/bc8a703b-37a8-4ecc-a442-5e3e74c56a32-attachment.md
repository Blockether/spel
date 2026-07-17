## GET http://localhost:42283/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 17 Jul 2026 15:37:31 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42283/health' \
  -H 'Authorization: Bearer test-token'
```
