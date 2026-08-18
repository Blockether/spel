## GET http://localhost:35489/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 18 Aug 2026 00:20:22 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35489/health' \
  -H 'X-Service: billing'
```
