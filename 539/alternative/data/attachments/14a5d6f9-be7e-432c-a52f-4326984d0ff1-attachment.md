## GET http://localhost:46137/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 09 Apr 2026 18:15:21 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46137/health' \
  -H 'X-Service: billing'
```
