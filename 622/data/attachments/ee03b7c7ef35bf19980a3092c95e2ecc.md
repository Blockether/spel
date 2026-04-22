## GET http://localhost:39695/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 22 Apr 2026 17:59:29 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39695/health' \
  -H 'X-Service: billing'
```
