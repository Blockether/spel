## GET http://localhost:33139/health → 200 OK

### Request Headers
```
X-Service: billing
```

### Response Headers
```
content-length: 15
content-type: application/json
date: Mon, 05 Oct 2026 06:46:06 GMT
```

### Response Body
```json
{
  "status": "ok"
}
```

### cURL
```bash
curl 'http://localhost:33139/health' \
  -H 'X-Service: billing'
```
