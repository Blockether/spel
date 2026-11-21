## GET http://localhost:46765/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 21 Nov 2026 18:36:22 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:46765/health' \
  -H 'X-Service: billing'
```
