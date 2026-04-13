## GET http://localhost:39847/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 13 Apr 2026 08:31:59 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39847/health' \
  -H 'Authorization: Bearer test-token'
```
