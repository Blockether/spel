## GET http://localhost:33033/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 01 Aug 2026 08:57:36 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33033/health' \
  -H 'X-Service: billing'
```
