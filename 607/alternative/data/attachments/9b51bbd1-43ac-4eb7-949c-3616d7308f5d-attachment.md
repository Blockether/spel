## GET http://localhost:42907/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 15 Apr 2026 10:40:07 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42907/health' \
  -H 'X-Service: billing'
```
