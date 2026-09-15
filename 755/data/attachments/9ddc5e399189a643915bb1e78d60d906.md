## GET http://localhost:41291/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 15 Sep 2026 13:25:25 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41291/health' \
  -H 'Authorization: Bearer test-token'
```
