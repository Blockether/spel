## GET http://localhost:36627/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 30 Mar 2026 11:05:55 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36627/health' \
  -H 'Authorization: Bearer test-token'
```
