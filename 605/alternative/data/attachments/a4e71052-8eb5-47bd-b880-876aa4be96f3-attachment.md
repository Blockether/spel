## GET http://localhost:33059/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 14 Apr 2026 13:52:03 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33059/health' \
  -H 'Authorization: Bearer test-token'
```
