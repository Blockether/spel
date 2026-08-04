## GET http://localhost:42343/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 04 Aug 2026 22:12:00 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42343/health' \
  -H 'Authorization: Bearer test-token'
```
