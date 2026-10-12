## GET http://localhost:41205/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 12 Oct 2026 16:15:44 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41205/health' \
  -H 'X-Service: billing'
```
