## GET http://localhost:35977/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 03 Aug 2026 02:53:14 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35977/health' \
  -H 'Authorization: Bearer test-token'
```
