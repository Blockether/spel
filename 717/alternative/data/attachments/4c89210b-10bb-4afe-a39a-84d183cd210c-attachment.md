## GET http://localhost:38771/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 18 Aug 2026 08:15:53 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38771/health' \
  -H 'X-Service: billing'
```
