## GET http://localhost:38557/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 24 Oct 2026 08:08:33 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38557/health' \
  -H 'X-Service: billing'
```
