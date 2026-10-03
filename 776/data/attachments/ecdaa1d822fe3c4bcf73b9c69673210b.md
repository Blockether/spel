## GET http://localhost:43231/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 03 Oct 2026 14:58:15 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43231/health' \
  -H 'X-Service: billing'
```
