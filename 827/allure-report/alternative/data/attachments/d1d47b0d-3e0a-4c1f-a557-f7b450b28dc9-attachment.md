## GET http://localhost:42517/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 11 Nov 2026 21:05:48 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42517/health' \
  -H 'X-Service: billing'
```
