## PATCH http://localhost:46715/echo → 200 OK

### Request Headers
```
PATCH http://localhost:46715/echo
```

### Request Body
```json
{
  "email": "eve@new.com"
}
```

### Response Headers
```
content-length: 64
content-type: application/json
date: Thu, 26 Feb 2026 16:44:11 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "eve@new.com"
  }
}
```

### cURL
```bash
curl 'http://localhost:46715/echo' \
  -X PATCH \
  -d '{"email":"eve@new.com"}'
```
