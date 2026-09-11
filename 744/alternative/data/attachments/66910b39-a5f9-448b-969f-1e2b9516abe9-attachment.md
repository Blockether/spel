## GET http://localhost:46861/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 11 Sep 2026 10:19:09 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46861/health' \
  -H 'Authorization: Bearer test-token'
```
