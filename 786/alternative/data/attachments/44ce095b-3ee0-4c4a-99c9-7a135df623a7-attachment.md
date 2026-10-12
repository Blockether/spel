## GET http://localhost:44029/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 12 Oct 2026 16:15:41 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44029/health' \
  -H 'Authorization: Bearer test-token'
```
