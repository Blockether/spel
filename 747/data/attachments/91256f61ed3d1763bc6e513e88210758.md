## GET http://localhost:36249/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Fri, 11 Sep 2026 15:01:07 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:36249/health' \
  -H 'X-Service: billing'
```
