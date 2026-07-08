## GET http://localhost:36337/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 08 Jul 2026 16:44:37 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36337/health' \
  -H 'X-Service: billing'
```
