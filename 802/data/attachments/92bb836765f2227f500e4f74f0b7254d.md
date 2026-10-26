## GET http://localhost:41663/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 26 Oct 2026 14:21:35 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41663/health' \
  -H 'Authorization: Bearer test-token'
```
