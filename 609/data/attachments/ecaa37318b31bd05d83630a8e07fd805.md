## GET http://localhost:35357/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Thu, 16 Apr 2026 08:07:14 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:35357/health' \
  -H 'X-Service: billing'
```
