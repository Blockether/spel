## GET http://localhost:36387/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 15 Sep 2026 11:16:48 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36387/health' \
  -H 'Authorization: Bearer test-token'
```
