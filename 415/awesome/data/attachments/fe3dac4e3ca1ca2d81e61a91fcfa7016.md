## PATCH http://localhost:43587/echo → 200 OK

### Request Headers
```
PATCH http://localhost:43587/echo
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
date: Fri, 06 Mar 2026 15:23:39 GMT
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
curl 'http://localhost:43587/echo' \
  -X PATCH \
  -d '{"email":"eve@new.com"}'
```
