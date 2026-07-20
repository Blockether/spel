## GET http://localhost:40547/health → 200 OK

### Request Headers
```
X-Service: users
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 20 Jul 2026 17:42:28 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:40547/health' \
  -H 'X-Service: users'
```
