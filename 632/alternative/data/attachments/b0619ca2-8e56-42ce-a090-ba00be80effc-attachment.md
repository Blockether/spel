## GET http://localhost:39455/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 13 Jul 2026 11:57:10 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39455/health' \
  -H 'X-Service: billing'
```
