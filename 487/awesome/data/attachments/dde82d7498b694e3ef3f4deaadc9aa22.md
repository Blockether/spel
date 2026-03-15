## GET http://localhost:38211/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 15 Mar 2026 17:52:16 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38211/health' \
  -H 'X-Service: billing'
```
