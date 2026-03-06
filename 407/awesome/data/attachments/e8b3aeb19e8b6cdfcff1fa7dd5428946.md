## GET http://localhost:39385/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 06 Mar 2026 06:10:14 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:39385/health' \
  -H 'X-Service: billing'
```
