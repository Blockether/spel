## GET http://localhost:42641/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 29 Mar 2026 23:12:11 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:42641/health' \
  -H 'X-Service: billing'
```
