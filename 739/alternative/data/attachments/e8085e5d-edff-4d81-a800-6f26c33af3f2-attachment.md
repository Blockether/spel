## GET http://localhost:40447/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 11 Sep 2026 02:42:35 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40447/health' \
  -H 'Authorization: Bearer test-token'
```
