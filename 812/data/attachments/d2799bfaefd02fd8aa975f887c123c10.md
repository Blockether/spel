## GET http://localhost:41839/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 03 Nov 2026 16:28:09 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41839/health' \
  -H 'X-Service: billing'
```
