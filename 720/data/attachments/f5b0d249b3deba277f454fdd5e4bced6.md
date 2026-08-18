## GET http://localhost:46279/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 18 Aug 2026 10:14:46 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46279/health' \
  -H 'Authorization: Bearer test-token'
```
