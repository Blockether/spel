## GET http://localhost:44537/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 05 Oct 2026 07:50:48 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44537/health' \
  -H 'Authorization: Bearer test-token'
```
