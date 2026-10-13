## GET http://localhost:40991/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 13 Oct 2026 12:15:19 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40991/health' \
  -H 'Authorization: Bearer test-token'
```
