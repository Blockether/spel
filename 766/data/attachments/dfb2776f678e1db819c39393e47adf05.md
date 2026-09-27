## GET http://localhost:40371/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 27 Sep 2026 16:41:12 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40371/health' \
  -H 'X-Service: billing'
```
