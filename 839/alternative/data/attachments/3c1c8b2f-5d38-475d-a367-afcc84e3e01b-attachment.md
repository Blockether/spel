## GET http://localhost:45343/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 21 Nov 2026 18:56:56 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:45343/health' \
  -H 'X-Service: billing'
```
