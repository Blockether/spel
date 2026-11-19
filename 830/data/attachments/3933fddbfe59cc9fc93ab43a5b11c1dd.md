## GET http://localhost:37869/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 19 Nov 2026 12:49:10 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:37869/health' \
  -H 'X-Service: billing'
```
