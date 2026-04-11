## GET http://localhost:41429/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 11 Apr 2026 11:44:45 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41429/health' \
  -H 'X-Service: billing'
```
