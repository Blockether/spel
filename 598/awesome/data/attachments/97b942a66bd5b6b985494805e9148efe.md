## GET http://localhost:44441/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 13 Apr 2026 02:18:30 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44441/health' \
  -H 'Authorization: Bearer test-token'
```
