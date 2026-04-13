## GET http://localhost:45511/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 13 Apr 2026 08:32:03 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:45511/health' \
  -H 'X-Service: billing'
```
