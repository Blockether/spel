## GET http://localhost:33005/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 18 Aug 2026 00:20:17 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33005/health' \
  -H 'Authorization: Bearer test-token'
```
