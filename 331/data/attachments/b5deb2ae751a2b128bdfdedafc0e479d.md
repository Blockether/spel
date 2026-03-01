## GET http://localhost:36615/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sun, 01 Mar 2026 20:00:58 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36615/health' \
  -H 'X-Service: billing'
```
