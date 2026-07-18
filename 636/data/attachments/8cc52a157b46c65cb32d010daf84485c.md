## GET http://localhost:41205/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 18 Jul 2026 12:05:00 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41205/health' \
  -H 'Authorization: Bearer test-token'
```
