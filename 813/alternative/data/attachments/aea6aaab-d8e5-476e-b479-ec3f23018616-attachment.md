## GET http://localhost:39205/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 03 Nov 2026 17:51:40 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39205/health' \
  -H 'X-Service: billing'
```
