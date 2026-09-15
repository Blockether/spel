## GET http://localhost:40105/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Tue, 15 Sep 2026 12:11:23 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40105/health' \
  -H 'X-Service: users'
```
