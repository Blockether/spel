## GET http://localhost:41063/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 28 Jul 2026 15:14:03 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41063/health' \
  -H 'X-Service: billing'
```
