## GET http://localhost:39319/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Sat, 14 Mar 2026 11:05:06 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39319/health' \
  -H 'X-Service: users'
```
