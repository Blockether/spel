## GET http://localhost:43139/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 12 Oct 2026 10:35:59 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:43139/health' \
  -H 'X-Service: billing'
```
