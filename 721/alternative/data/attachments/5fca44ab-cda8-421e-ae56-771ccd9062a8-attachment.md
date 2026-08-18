## GET http://localhost:35313/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 18 Aug 2026 10:54:36 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35313/health' \
  -H 'X-Service: billing'
```
