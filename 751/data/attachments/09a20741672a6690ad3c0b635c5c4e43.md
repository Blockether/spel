## GET http://localhost:36823/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 14 Sep 2026 12:40:49 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36823/health' \
  -H 'X-Service: billing'
```
