## GET http://localhost:39095/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 05 Oct 2026 23:18:41 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39095/health' \
  -H 'Authorization: Bearer test-token'
```
