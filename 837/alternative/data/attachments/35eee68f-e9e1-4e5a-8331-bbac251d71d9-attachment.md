## GET http://localhost:37729/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 21 Nov 2026 18:25:23 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:37729/health' \
  -H 'Authorization: Bearer test-token'
```
