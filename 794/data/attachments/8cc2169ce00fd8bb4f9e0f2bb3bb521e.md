## GET http://localhost:33273/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 20 Oct 2026 23:37:44 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33273/health' \
  -H 'X-Service: billing'
```
