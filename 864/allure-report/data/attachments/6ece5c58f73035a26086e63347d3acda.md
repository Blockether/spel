## GET http://localhost:38721/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 23 Nov 2026 20:40:56 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:38721/health' \
  -H 'X-Service: users'
```
