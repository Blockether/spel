## GET http://localhost:37279/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 23 Nov 2026 10:48:51 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:37279/health' \
  -H 'X-Service: billing'
```
