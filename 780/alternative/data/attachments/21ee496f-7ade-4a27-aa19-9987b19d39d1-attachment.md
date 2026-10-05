## GET http://localhost:44141/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 05 Oct 2026 07:50:53 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:44141/health' \
  -H 'X-Service: users'
```
