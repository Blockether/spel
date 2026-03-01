## PATCH http://localhost:46627/echo → 200 OK

### Request Headers
```
PATCH http://localhost:46627/echo
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
date: Sun, 01 Mar 2026 22:45:13 GMT
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
curl 'http://localhost:46627/echo' \
  -X PATCH \
  -d '{"email":"eve@new.com"}'
```
