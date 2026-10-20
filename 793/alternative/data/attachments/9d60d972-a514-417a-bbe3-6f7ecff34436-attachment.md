## GET http://localhost:39655/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 20 Oct 2026 21:23:26 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39655/health' \
  -H 'Authorization: Bearer test-token'
```
