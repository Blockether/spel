## GET http://localhost:36433/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 18 Aug 2026 18:59:21 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36433/health' \
  -H 'X-Service: billing'
```
