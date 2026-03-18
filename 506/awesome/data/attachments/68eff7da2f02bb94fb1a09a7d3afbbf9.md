## GET http://localhost:33455/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 18 Mar 2026 16:44:14 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33455/health' \
  -H 'Authorization: Bearer test-token'
```
