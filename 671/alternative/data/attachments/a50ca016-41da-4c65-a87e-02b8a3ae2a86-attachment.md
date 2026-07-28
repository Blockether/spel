## GET http://localhost:44543/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 28 Jul 2026 17:00:31 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44543/health' \
  -H 'X-Service: billing'
```
