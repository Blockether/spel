## GET http://localhost:41243/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 18 Aug 2026 12:20:08 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41243/health' \
  -H 'Authorization: Bearer test-token'
```
