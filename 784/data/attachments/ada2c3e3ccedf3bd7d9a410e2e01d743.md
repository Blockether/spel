## GET http://localhost:36933/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 05 Oct 2026 23:18:45 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36933/health' \
  -H 'X-Service: billing'
```
