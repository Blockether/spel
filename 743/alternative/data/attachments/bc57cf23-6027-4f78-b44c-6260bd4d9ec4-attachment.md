## GET http://localhost:40821/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 11 Sep 2026 08:05:08 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40821/health' \
  -H 'X-Service: users'
```
