## GET http://localhost:33139/health → 200 OK

### Request Headers
```
Authorization: Bearer test-token
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 27 Aug 2026 14:34:32 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33139/health' \
  -H 'Authorization: Bearer test-token'
```
