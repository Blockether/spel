## GET http://localhost:40861/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 13 Apr 2026 02:18:34 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40861/health' \
  -H 'X-Service: billing'
```
