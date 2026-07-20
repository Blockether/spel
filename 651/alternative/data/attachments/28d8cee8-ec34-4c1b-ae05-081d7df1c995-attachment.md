## GET http://localhost:45659/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 20 Jul 2026 19:21:19 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:45659/health' \
  -H 'X-Service: billing'
```
