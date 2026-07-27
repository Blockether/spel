## GET http://localhost:41719/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 27 Jul 2026 12:08:16 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41719/health' \
  -H 'X-Service: billing'
```
