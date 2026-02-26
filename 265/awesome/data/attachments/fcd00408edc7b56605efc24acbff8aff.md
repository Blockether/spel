## GET http://localhost:41911/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 26 Feb 2026 09:04:26 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41911/health' \
  -H 'X-Service: billing'
```
