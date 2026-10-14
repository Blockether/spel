## GET http://localhost:46627/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 14 Oct 2026 18:16:30 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46627/health' \
  -H 'Authorization: Bearer test-token'
```
