## GET http://localhost:41599/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 15 Sep 2026 10:30:42 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41599/health' \
  -H 'Authorization: Bearer test-token'
```
