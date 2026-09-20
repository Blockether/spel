## GET http://localhost:39475/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 20 Sep 2026 15:19:28 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39475/health' \
  -H 'Authorization: Bearer test-token'
```
