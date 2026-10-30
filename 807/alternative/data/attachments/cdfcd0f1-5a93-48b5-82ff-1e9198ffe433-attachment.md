## GET http://localhost:32953/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 30 Oct 2026 12:21:25 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:32953/health' \
  -H 'Authorization: Bearer test-token'
```
