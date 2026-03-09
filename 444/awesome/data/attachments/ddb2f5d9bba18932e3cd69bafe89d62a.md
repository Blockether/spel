## PATCH http://localhost:38051/echo → 200 OK

### Request Headers
```
PATCH http://localhost:38051/echo
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
date: Mon, 09 Mar 2026 18:09:47 GMT
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
curl 'http://localhost:38051/echo' \
  -X PATCH \
  -d '{"email":"eve@new.com"}'
```
