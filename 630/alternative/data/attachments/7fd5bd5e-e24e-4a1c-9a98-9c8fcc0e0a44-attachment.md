## GET http://localhost:42813/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 08 Jul 2026 16:48:52 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42813/health' \
  -H 'X-Service: billing'
```
