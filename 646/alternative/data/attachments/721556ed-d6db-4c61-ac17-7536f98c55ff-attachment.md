## GET http://localhost:37783/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 20 Jul 2026 17:33:00 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:37783/health' \
  -H 'Authorization: Bearer test-token'
```
