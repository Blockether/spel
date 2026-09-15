## GET http://localhost:38265/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 15 Sep 2026 13:25:29 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38265/health' \
  -H 'X-Service: billing'
```
