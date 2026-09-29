## GET http://localhost:45505/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 29 Sep 2026 15:44:57 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:45505/health' \
  -H 'Authorization: Bearer test-token'
```
