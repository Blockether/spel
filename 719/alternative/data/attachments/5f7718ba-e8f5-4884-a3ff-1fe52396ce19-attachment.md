## GET http://localhost:44659/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 18 Aug 2026 09:35:09 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44659/health' \
  -H 'Authorization: Bearer test-token'
```
