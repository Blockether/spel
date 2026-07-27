## GET http://localhost:41259/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 27 Jul 2026 12:46:46 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:41259/health' \
  -H 'X-Service: users'
```
