## GET http://localhost:33371/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 14 Oct 2026 13:23:28 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33371/health' \
  -H 'X-Service: billing'
```
