## GET http://localhost:35557/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 10 Aug 2026 08:21:19 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35557/health' \
  -H 'Authorization: Bearer test-token'
```
