## GET http://localhost:46481/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 10 Apr 2026 08:22:08 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46481/health' \
  -H 'X-Service: billing'
```
