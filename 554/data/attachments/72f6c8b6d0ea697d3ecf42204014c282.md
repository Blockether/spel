## GET http://localhost:40319/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 11 Apr 2026 11:28:57 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40319/health' \
  -H 'X-Service: billing'
```
