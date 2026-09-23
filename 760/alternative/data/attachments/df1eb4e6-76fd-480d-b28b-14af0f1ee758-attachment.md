## GET http://localhost:39749/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 23 Sep 2026 11:27:01 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39749/health' \
  -H 'Authorization: Bearer test-token'
```
