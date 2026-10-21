## GET http://localhost:34075/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Wed, 21 Oct 2026 00:33:38 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:34075/health' \
  -H 'X-Service: billing'
```
